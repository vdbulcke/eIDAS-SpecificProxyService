package ee.ria.eidas.proxy.specific.web;

import ee.ria.eidas.proxy.specific.config.SpecificProxyServiceProperties;
import ee.ria.eidas.proxy.specific.config.SpecificProxyServiceProperties.CookieProperties;
import ee.ria.eidas.proxy.specific.error.BadRequestException;
import ee.ria.eidas.proxy.specific.error.RequestDeniedException;
import ee.ria.eidas.proxy.specific.service.SpecificProxyService;
import ee.ria.eidas.proxy.specific.storage.EidasNodeCommunication;
import ee.ria.eidas.proxy.specific.storage.SpecificProxyServiceCommunication;
import ee.ria.eidas.proxy.specific.storage.SpecificProxyServiceCommunication.CorrelatedRequestsHolder;
import eu.eidas.auth.commons.attribute.AttributeDefinition;
import eu.eidas.auth.commons.light.ILightRequest;
import eu.eidas.auth.commons.protocol.impl.SamlNameIdFormat;
import eu.eidas.specificcommunication.exception.SpecificCommunicationException;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseCookie.ResponseCookieBuilder;
import org.springframework.stereotype.Controller;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.http.HttpHeaders;

import javax.servlet.http.HttpServletResponse;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Pattern;
import javax.validation.constraints.Size;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import static ee.ria.eidas.proxy.specific.error.SpecificProxyServiceExceptionHandler.MULTIPLE_INSTANCES_OF_PARAMETER_IS_NOT_ALLOWED;
import static ee.ria.eidas.proxy.specific.web.filter.HttpRequestHelper.getStringParameterValue;
import static java.util.Arrays.asList;
import static java.util.stream.Collectors.toList;


@Slf4j
@Validated
@Controller
public class ProxyServiceRequestController {

    public static final String ENDPOINT_PROXY_SERVICE_REQUEST = "/ProxyServiceRequest";
    private static final List<String> ISO_COUNTRY_CODES = asList(Locale.getISOCountries());

    @Autowired
    private SpecificProxyServiceProperties specificProxyServiceProperties;

    @Autowired
    private SpecificProxyService specificProxyService;

    @Autowired
    private EidasNodeCommunication eidasNodeCommunication;

    @Autowired
    private SpecificProxyServiceCommunication specificProxyServiceCommunication;

    @GetMapping(value = ENDPOINT_PROXY_SERVICE_REQUEST)
    public ModelAndView get(@Validated RequestParameters request, HttpServletResponse response) throws SpecificCommunicationException {
        return execute(request, response);
    }

    @PostMapping(value = ENDPOINT_PROXY_SERVICE_REQUEST)
    public ModelAndView post(@Validated RequestParameters request, HttpServletResponse response) throws SpecificCommunicationException {
        return execute(request, response);
    }

    private ModelAndView execute(RequestParameters request, HttpServletResponse response) throws SpecificCommunicationException {
        String tokenBase64 = getStringParameterValue(request.getToken());

        ILightRequest incomingLightRequest = eidasNodeCommunication.getAndRemoveRequest(tokenBase64);
        validateLightRequest(incomingLightRequest);

        CorrelatedRequestsHolder correlatedRequestsHolder = specificProxyService.createOidcAuthenticationRequest(incomingLightRequest);
        specificProxyServiceCommunication.putIdpRequest(correlatedRequestsHolder.getIdpAuthenticationRequestState(), correlatedRequestsHolder);


        String state = correlatedRequestsHolder.getIdpAuthenticationRequestState();
        addStateCSRFCookie(response, state);

        return new ModelAndView("redirect:" + correlatedRequestsHolder.getIdpAuthenticationRequest());
    }

    private void validateLightRequest(ILightRequest incomingLightRequest) {
        if (incomingLightRequest == null)
            throw new BadRequestException("Invalid token");

        if (!specificProxyServiceProperties.getSupportedSpTypes().contains(incomingLightRequest.getSpType()))
            throw new RequestDeniedException("Service provider type not supported. Allowed types: "
                    + specificProxyServiceProperties.getSupportedSpTypes(), incomingLightRequest.getId());

        if (!ISO_COUNTRY_CODES.contains(incomingLightRequest.getCitizenCountryCode()))
            throw new BadRequestException("CitizenCountryCode not in 3166-1-alpha-2 format");

        if (incomingLightRequest.getNameIdFormat() != null && SamlNameIdFormat.fromString(incomingLightRequest.getNameIdFormat()) == null)
            throw new BadRequestException("Invalid NameIdFormat");

        if (containsLegalPersonAndNaturalPersonAttributes(incomingLightRequest))
            throw new BadRequestException("Request may not contain both legal person and natural person attributes");
    }

    private boolean containsLegalPersonAndNaturalPersonAttributes(ILightRequest incomingLightRequest) {
        List<String> requestAttributesByFriendlyName = incomingLightRequest.getRequestedAttributes().getAttributeMap().keySet()
                .stream().map(AttributeDefinition::getFriendlyName).collect(toList());
        return !Collections.disjoint(asList("LegalName", "LegalPersonIdentifier"), requestAttributesByFriendlyName) &&
                !Collections.disjoint(asList("GivenName", "FamilyName", "PersonIdentifier", "DateOfBirth"), requestAttributesByFriendlyName);
    }


    private void addStateCSRFCookie(HttpServletResponse response, String state) {

        CookieProperties props = specificProxyServiceProperties.getOidc().getStateCookie();
        if(props == null) {
            return;
        }

        String cookieName = "eidas_service_proxy_oidc_csrf";
        // String cookieName = props.getStateCookieName();
        // if (cookieName == null) {
        //     return;
        // }
        
        ResponseCookieBuilder csrfBuilder = ResponseCookie.from(cookieName, state);

    
        if(props.getHttpOnly()) {
            csrfBuilder.httpOnly(true);
        }

        if(props.getSecure()) {
            csrfBuilder.secure(true);
        }

        String domain = props.getDomain();
        if(domain != null) {
            csrfBuilder.domain(domain);
        }

        String path = props.getPath();
        if(path != null) {
            csrfBuilder.path(path);
        }

        String sameSite = props.getSameSite();
        if(sameSite != null) {
            csrfBuilder.sameSite(sameSite);
        }

        int maxAge = props.getMaxAge();
        if(maxAge != 0) {
            csrfBuilder.maxAge(maxAge);
        }
            
        ResponseCookie csrf = csrfBuilder.build();
        response.addHeader(HttpHeaders.SET_COOKIE, csrf.toString() );
        
    }

    @Data
    public static class RequestParameters {

        @NotNull
        @Size(max = 1, message = MULTIPLE_INSTANCES_OF_PARAMETER_IS_NOT_ALLOWED)
        private List<@Pattern(regexp = "^[A-Za-z0-9+/=]{1,1000}$", message = "only base64 characters allowed") String> token;
    }
}
