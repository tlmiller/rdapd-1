package net.apnic.rdapd.domain.controller;

import javax.servlet.http.HttpServletRequest;

import net.apnic.rdapd.history.ObjectClass;
import net.apnic.rdapd.history.ObjectHistory;
import net.apnic.rdapd.history.ObjectIndex;
import net.apnic.rdapd.history.ObjectKey;
import net.apnic.rdapd.history.Revision;
import net.apnic.rdapd.rdap.controller.RDAPControllerUtil;
import net.apnic.rdapd.rdap.controller.RDAPResponseMaker;
import net.apnic.rdapd.rdap.TopLevelObject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

/**
 * Rest Controller for the RDAP /domain path segment.
 *
 * Controller is responsible for dealing with current state RDAP path segments.
 */
@RestController
@RequestMapping("/domain")
public class DomainRouteController
{
    private final static Logger LOGGER = LoggerFactory.getLogger(DomainRouteController.class);

    private final ObjectIndex objectIndex;
    private final RDAPControllerUtil rdapControllerUtil;

    @Autowired
    public DomainRouteController(ObjectIndex objectIndex,
        RDAPResponseMaker rdapResponseMaker)
    {
        this.objectIndex = objectIndex;
        this.rdapControllerUtil = new RDAPControllerUtil(rdapResponseMaker);
    }

    @RequestMapping(value="/{handle:.+}", method=RequestMethod.GET)
    public ResponseEntity<TopLevelObject> domainPathGet(
        HttpServletRequest request,
        @PathVariable("handle") String handle)
    {
        LOGGER.debug("domain GET path query for {}", handle);

        return rdapControllerUtil.singleObjectResponse(request,
            objectIndex.historyForObject(
                new ObjectKey(ObjectClass.DOMAIN, handle))
            .flatMap(ObjectHistory::mostCurrent)
            .map(Revision::getContents).orElse(null));
    }

    @RequestMapping(value="/{handle:.+}", method=RequestMethod.HEAD)
    public ResponseEntity<TopLevelObject> domainPathHead(
        HttpServletRequest request,
        @PathVariable("handle") String handle)
    {
        LOGGER.debug("domain HEAD path query for {}", handle);

        return rdapControllerUtil.singleObjectResponse(request,
            objectIndex.historyForObject(
                new ObjectKey(ObjectClass.DOMAIN, handle))
            .flatMap(ObjectHistory::mostCurrent)
            .map(Revision::getContents).orElse(null));
    }
}
