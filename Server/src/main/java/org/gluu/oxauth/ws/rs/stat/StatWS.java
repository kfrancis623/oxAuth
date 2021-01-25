package org.gluu.oxauth.ws.rs.stat;

import net.agkn.hll.HLL;
import org.apache.commons.lang.StringUtils;
import org.gluu.oxauth.model.configuration.AppConfiguration;
import org.gluu.oxauth.model.error.ErrorResponseFactory;
import org.gluu.oxauth.model.session.SessionClient;
import org.gluu.oxauth.model.stat.StatEntry;
import org.gluu.oxauth.model.token.TokenErrorResponseType;
import org.gluu.oxauth.security.Identity;
import org.gluu.oxauth.service.stat.StatService;
import org.gluu.oxauth.util.ServerUtil;
import org.gluu.persist.PersistenceEntryManager;
import org.gluu.search.filter.Filter;
import org.slf4j.Logger;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * @author Yuriy Zabrovarnyy
 */
@ApplicationScoped
@Path("/stat")
public class StatWS {

    private static final int DEFAULT_WS_INTERVAL_LIMIT_IN_SECONDS = 60;

    @Inject
    private Logger log;

    @Inject
    private PersistenceEntryManager entryManager;

    @Inject
    private ErrorResponseFactory errorResponseFactory;

    @Inject
    private Identity identity;

    @Inject
    private StatService statService;

    @Inject
    private AppConfiguration appConfiguration;

    private long lastProcessedAt;

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Response statGet(@HeaderParam("Authorization") String authorization, @QueryParam("month") String month) {
        return stat(month);
    }

    @POST
    @Produces(MediaType.APPLICATION_JSON)
    public Response statPost(@HeaderParam("Authorization") String authorization, @FormParam("month") String month) {
        return stat(month);
    }

    public Response stat(String month) {
        return null;
    }

    private List<String> validateMonth(String month) {
        if (StringUtils.isBlank(month)) {
            throw errorResponseFactory.createWebApplicationException(Response.Status.BAD_REQUEST, TokenErrorResponseType.INVALID_REQUEST, "`month` parameter can't be blank and should be in format yyyyMM (e.g. 202012)");
        }

        month = ServerUtil.urlDecode(month);

        List<String> months = new ArrayList<>();
        for (String m : month.split(" ")) {
            m = m.trim();
            if (m.length() == 6) {
                months.add(m);
            }
        }

        if (months.isEmpty()) {
            throw errorResponseFactory.createWebApplicationException(Response.Status.BAD_REQUEST, TokenErrorResponseType.INVALID_REQUEST, "`month` parameter can't be blank and should be in format yyyyMM (e.g. 202012)");
        }

        return months;
    }

    private boolean allowToRun() {
        int interval = appConfiguration.getStatWebServiceIntervalLimitInSeconds();
        if (interval <= 0) {
            interval = DEFAULT_WS_INTERVAL_LIMIT_IN_SECONDS;
        }

        long timerInterval = interval * 1000;

        long timeDiff = System.currentTimeMillis() - lastProcessedAt;

        return timeDiff >= timerInterval;
    }
}