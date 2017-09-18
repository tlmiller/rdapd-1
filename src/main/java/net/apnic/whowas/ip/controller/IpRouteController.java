package net.apnic.whowas.ip.controller;

import javax.servlet.http.HttpServletRequest;

import net.apnic.whowas.error.MalformedRequestException;
import net.apnic.whowas.rdap.controller.RDAPControllerUtil;
import net.apnic.whowas.rdap.TopLevelObject;
import net.apnic.whowas.types.IpInterval;
import net.apnic.whowas.types.Parsing;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.HandlerMapping;

@RestController
@RequestMapping("/ip")
public class IpRouteController
{
    private final static Logger LOGGER = LoggerFactory.getLogger(IpRouteController.class);

    private final RDAPControllerUtil rdapControllerUtil;

    @Autowired
    public IpRouteController(RDAPControllerUtil rdapControllerUtil)
    {
        this.rdapControllerUtil = rdapControllerUtil;
    }

    @RequestMapping(value="/**", method=RequestMethod.GET)
    public ResponseEntity<TopLevelObject> ipPathGet(HttpServletRequest request)
    {
        String param = (String)request.getAttribute(
            HandlerMapping.PATH_WITHIN_HANDLER_MAPPING_ATTRIBUTE);
        LOGGER.debug("ip GET path query for {}", param);

        IpInterval range = null;

        try
        {
            range = Parsing.parseCIDRInterval(param.substring(4));
        }
        catch(Exception ex)
        {
            throw new MalformedRequestException(ex);
        }

        return rdapControllerUtil.mostCurrentResponseGet(request, range);
    }

    @RequestMapping(value="/**", method=RequestMethod.HEAD)
    public ResponseEntity<TopLevelObject> ipPathHead(HttpServletRequest request)
    {
        String param = (String)request.getAttribute(
            HandlerMapping.PATH_WITHIN_HANDLER_MAPPING_ATTRIBUTE);
        LOGGER.debug("ip HEAD path query for {}", param);

        IpInterval range = null;

        try
        {
            range = Parsing.parseCIDRInterval(param.substring(4));
        }
        catch(Exception ex)
        {
            throw new MalformedRequestException(ex);
        }

        return rdapControllerUtil.mostCurrentResponseGet(request, range);
    }
}