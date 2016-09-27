package net.apnic.whowas.loaders;

import net.apnic.whowas.history.History;
import net.apnic.whowas.history.RpslRecord;
import net.apnic.whowas.intervaltree.IntervalTree;
import net.apnic.whowas.intervaltree.avl.AvlTree;
import net.apnic.whowas.progress.Bar;
import net.apnic.whowas.types.IP;
import net.apnic.whowas.types.IpInterval;
import net.apnic.whowas.types.Parsing;
import net.apnic.whowas.types.Tuple;
import net.ripe.db.whois.common.domain.CIString;
import net.ripe.db.whois.common.rpsl.AttributeType;
import net.ripe.db.whois.common.rpsl.ObjectType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcOperations;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public final class RipeDbLoader implements Loader {
    private static final Logger LOGGER = LoggerFactory.getLogger(RipeDbLoader.class);

    private final JdbcOperations operations;

    public RipeDbLoader(JdbcOperations jdbcOperations) {
        this.operations = jdbcOperations;
    }

    @Override
    public IntervalTree<IP, History, IpInterval> loadTree() throws Exception {
        AvlTree<IP, History, IpInterval> avlTree = new AvlTree<>();
        loadInto(r -> avlTree.insert(Parsing.parseInterval(r.getPrimaryKey()), r));
        return avlTree;
    }

    private void loadInto(Consumer<History> consumer) {
        LOGGER.info("Begin database run");

        Function<String, String> query =
                tbl -> "SELECT object_type, pkey, object_id, sequence_id, timestamp, object FROM " + tbl + " " +
                        "WHERE object_type in (0, 2, 3, 5, 6, 9, 10, 11, 17, 18)";

        List<RpslRecord> all = operations.query(query.apply("history"),
                (rs, i) -> {
                    return new RpslRecord(rs.getInt(1), rs.getString(2), rs.getLong(3), rs.getLong(4),
                            fromStamp(rs.getLong(5)), LocalDateTime.MAX, rs.getString(6), Collections.emptyList());
                }
        );
        all.addAll(operations.query(query.apply("last"),
                (rs, i) -> {
                    return new RpslRecord(rs.getInt(1), rs.getString(2), rs.getLong(3), rs.getLong(4),
                            fromStamp(rs.getLong(5)),LocalDateTime.MAX, rs.getString(6), Collections.emptyList());
                }
        ));
        LOGGER.info("Fetched {} records", all.size());

        // Construct an index of all objects by type and primary key
        Map<Tuple<ObjectType, CIString>, List<RpslRecord>> index = all.parallelStream().collect(HashMap::new, (m, r) -> {
            Optional.ofNullable(OBJECT_CODES.get(r.getObjectType()))
                    .map(t -> new Tuple<>(t, CIString.ciString(r.getPrimaryKey())))
                    .ifPresent(t -> m.merge(t, Collections.singletonList(r), RipeDbLoader::listMerge));
        }, (m, m1) -> m1.forEach((k, v) -> m.merge(k, v, RipeDbLoader::listMerge)));
        LOGGER.info("Created index of all pkeys");

        // Update all RpslRecords to have chained whence/until dates
        index.replaceAll((k, v) -> {
            v.sort(null);
            return linkDates(v);
        });
        LOGGER.info("Linked dates for all records: {} total", index.keySet().size());

        // Construct a query tree of History objects
        final Bar bar = new Bar(index.keySet().size(), 6, s -> LOGGER.info("Loading records {}", s));
        index.keySet().stream().sequential()    // must be sequential for inserts into the AVL tree
                // update progress
                .peek(z -> bar.inc())

                // Look for just inetnums (later: inetnums, aut-nums, inet6nums; same algorithm)
                .filter(o -> o.fst() == ObjectType.INETNUM)

                // Consider all instances of this object pkey
                .map(index::get)

                // Eliminate deletions and short-lived objects
                // nb: short-lived objects should be merged in ::fromStream below, and deleted objects should
                //   leave some kind of marker in the history output to distinguish from 'no data'
                .map(o -> o.stream().filter(r -> !r.isDelete() && !r.isPrivate()))

                // Convert to a History of the object
                .flatMap(History::fromStream)

                // Split up each History around its dependents
                .map(h -> h.flatMap(hd -> splitOnDependents(index, hd)))

                // Get rid of any histories which no longer exist
                .flatMap(o -> o.map(Stream::of).orElse(Stream.empty()))

                // Cache the JSON output
//                .map(History::cached)

                // Insert into tree
                .forEach(consumer);
    }

    private static LocalDateTime fromStamp(long stamp) {
        return Instant.ofEpochSecond(stamp).atZone(ZoneId.systemDefault()).toLocalDateTime();
    }

    private static <T> List<T> listMerge(List<T> a, List<T> b) {
        List<T> l = new ArrayList<>(a.size() + b.size());
        l.addAll(a);
        l.addAll(b);
        return l;
    }

    private static List<RpslRecord> linkDates(List<RpslRecord> in) {
        ListIterator<RpslRecord> i = in.listIterator();
        List<RpslRecord> out = new ArrayList<>(in.size());
        while (i.hasNext()) {
            RpslRecord r = i.next();
            if (i.hasNext()) {
                out.add(r.withUntil(i.next().getWhence()));
                i.previous();
            } else {
                out.add(r);
            }
        }
        return out;
    }

    // Ensure an RPSL record is included as a child for the relevant time span
    private void addToDataSet(Set<RpslRecord> set, RpslRecord child) {
        // Invariant: no times overlap in the set

        // For every object in the set covering the span of the child
        Set<RpslRecord> toSplit = set.stream()
                .filter(p -> p.getWhence().isBefore(child.getUntil()))
                .filter(p -> p.getUntil().isAfter(child.getWhence()))
                .collect(Collectors.toSet());

        LOGGER.debug("Number of split objects for {}: {}", child, toSplit);

        toSplit.forEach(r -> {
            set.remove(r);
            // The object before this child was valid  (or none, if whences line up)
            if (r.getWhence().isBefore(child.getWhence())) {
                RpslRecord[] beforeAndDuring = r.splitAt(child.getWhence());
                LOGGER.debug("Adding BEFORE record {}", beforeAndDuring[0]);
                set.add(beforeAndDuring[0]);
                r = beforeAndDuring[1];
            }

            // The object after this child was valid   (or none, if untils line up)
            if (r.getUntil().isAfter(child.getUntil())) {
                RpslRecord[] duringAndAfter = r.splitAt(child.getUntil());
                LOGGER.debug("Adding AFTER record {}", duringAndAfter[1]);
                set.add(duringAndAfter[1]);
                r = duringAndAfter[0];
            }

            // The object while this child was valid
            set.add(r.withChild(child));
        });
    }

    // Split an RPSL record around changes to its dependent records
    private Stream<RpslRecord> splitOnDependents(Map<Tuple<ObjectType, CIString>, List<RpslRecord>> index, RpslRecord parent) {
        return parent.getRpslObject().map(o -> {
            // Get all the child object names, associated with their (simplified) type code
            Set<Tuple<ObjectType, CIString>> children = Stream.concat(Stream.concat(
                    o.getValuesForAttribute(AttributeType.TECH_C, AttributeType.ADMIN_C, AttributeType.ZONE_C)
                            .stream().map(s -> new Tuple<>(ObjectType.ROLE, s)),
                    o.getValuesForAttribute(AttributeType.MNT_IRT)
                            .stream().map(s -> new Tuple<>(ObjectType.IRT, s))),
                    o.getValuesForAttribute(AttributeType.MNT_BY, AttributeType.MNT_LOWER, AttributeType.MNT_ROUTES)
                            .stream().map(s -> new Tuple<>(ObjectType.MNTNER, s))
            ).collect(Collectors.toSet());

            LOGGER.debug("Checking dependents for {} ({})", parent, children);
            Set<RpslRecord> result = new HashSet<>(Collections.singleton(parent));
            children.stream().sequential()
                    .map(index::get)
                    .filter(p -> p != null)
                    .flatMap(Collection::stream)
                    .filter(q -> q.getWhence().compareTo(parent.getUntil()) < 0)
                    .filter(q -> parent.getWhence().compareTo(q.getUntil()) < 0)
                    .forEach(c -> addToDataSet(result, c));
            LOGGER.debug("Split parent into {} sub-ranges", result.size());

            return result.stream();
        }).orElse(Stream.empty());
    }

    // Presence in the map serves as a proxy for relevance to this application
    private static final Map<Integer, ObjectType> OBJECT_CODES = Stream.of(
            new Tuple<>(3, ObjectType.DOMAIN),
            new Tuple<>(6, ObjectType.INETNUM),
            new Tuple<>(9, ObjectType.MNTNER),
            new Tuple<>(10, ObjectType.ROLE),       /* PERSON maps onto ROLE for easier subsequent processing */
            new Tuple<>(11, ObjectType.ROLE),
            new Tuple<>(18, ObjectType.ORGANISATION),
            new Tuple<>(5, ObjectType.INET6NUM),
            new Tuple<>(0, ObjectType.AS_BLOCK),
            new Tuple<>(2, ObjectType.AUT_NUM),
            new Tuple<>(17, ObjectType.IRT)
    ).collect(Collectors.toMap(Tuple::fst, Tuple::snd));
}