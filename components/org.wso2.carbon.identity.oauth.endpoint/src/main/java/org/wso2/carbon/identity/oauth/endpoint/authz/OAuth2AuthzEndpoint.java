/*
 * Copyright (c) 2013, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.wso2.carbon.identity.oauth.endpoint.authz;

import com.nimbusds.jwt.SignedJWT;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.collections.MapUtils;
import org.apache.commons.lang.ArrayUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.http.NameValuePair;
import org.apache.http.client.utils.URIBuilder;
import org.apache.oltu.oauth2.as.request.OAuthAuthzRequest;
import org.apache.oltu.oauth2.as.response.OAuthASResponse;
import org.apache.oltu.oauth2.common.OAuth;
import org.apache.oltu.oauth2.common.error.OAuthError;
import org.apache.oltu.oauth2.common.exception.OAuthProblemException;
import org.apache.oltu.oauth2.common.exception.OAuthSystemException;
import org.apache.oltu.oauth2.common.message.OAuthResponse;
import org.json.JSONException;
import org.json.JSONObject;
import org.wso2.carbon.context.PrivilegedCarbonContext;
import org.wso2.carbon.identity.application.authentication.framework.AuthenticatorFlowStatus;
import org.wso2.carbon.identity.application.authentication.framework.CommonAuthenticationHandler;
import org.wso2.carbon.identity.application.authentication.framework.cache.AuthenticationResultCacheEntry;
import org.wso2.carbon.identity.application.authentication.framework.context.AuthHistory;
import org.wso2.carbon.identity.application.authentication.framework.context.SessionContext;
import org.wso2.carbon.identity.application.authentication.framework.handler.request.impl.consent.ClaimMetaData;
import org.wso2.carbon.identity.application.authentication.framework.handler.request.impl.consent.ConsentClaimsData;
import org.wso2.carbon.identity.application.authentication.framework.handler.request.impl.consent.exception.SSOConsentServiceException;
import org.wso2.carbon.identity.application.authentication.framework.model.AuthenticatedUser;
import org.wso2.carbon.identity.application.authentication.framework.model.AuthenticationResult;
import org.wso2.carbon.identity.application.authentication.framework.model.CommonAuthRequestWrapper;
import org.wso2.carbon.identity.application.authentication.framework.model.CommonAuthResponseWrapper;
import org.wso2.carbon.identity.application.authentication.framework.util.FrameworkConstants;
import org.wso2.carbon.identity.application.authentication.framework.util.FrameworkUtils;
import org.wso2.carbon.identity.application.common.model.Claim;
import org.wso2.carbon.identity.application.common.model.ClaimMapping;
import org.wso2.carbon.identity.application.common.model.ServiceProvider;
import org.wso2.carbon.identity.application.common.model.ServiceProviderProperty;
import org.wso2.carbon.identity.base.IdentityConstants;
import org.wso2.carbon.identity.claim.metadata.mgt.ClaimMetadataHandler;
import org.wso2.carbon.identity.claim.metadata.mgt.exception.ClaimMetadataException;
import org.wso2.carbon.identity.claim.metadata.mgt.model.ExternalClaim;
import org.wso2.carbon.identity.core.ServiceURLBuilder;
import org.wso2.carbon.identity.core.URLBuilderException;
import org.wso2.carbon.identity.core.util.IdentityTenantUtil;
import org.wso2.carbon.identity.core.util.IdentityUtil;
import org.wso2.carbon.identity.oauth.IdentityOAuthAdminException;
import org.wso2.carbon.identity.oauth.cache.AuthorizationGrantCache;
import org.wso2.carbon.identity.oauth.cache.AuthorizationGrantCacheEntry;
import org.wso2.carbon.identity.oauth.cache.AuthorizationGrantCacheKey;
import org.wso2.carbon.identity.oauth.cache.SessionDataCache;
import org.wso2.carbon.identity.oauth.cache.SessionDataCacheEntry;
import org.wso2.carbon.identity.oauth.cache.SessionDataCacheKey;
import org.wso2.carbon.identity.oauth.common.OAuth2ErrorCodes;
import org.wso2.carbon.identity.oauth.common.OAuthConstants;
import org.wso2.carbon.identity.oauth.common.exception.InvalidOAuthClientException;
import org.wso2.carbon.identity.oauth.config.OAuthServerConfiguration;
import org.wso2.carbon.identity.oauth.dao.OAuthAppDO;
import org.wso2.carbon.identity.oauth.dto.OAuthErrorDTO;
import org.wso2.carbon.identity.oauth.endpoint.OAuthRequestWrapper;
import org.wso2.carbon.identity.oauth.endpoint.exception.ConsentHandlingFailedException;
import org.wso2.carbon.identity.oauth.endpoint.exception.InvalidRequestException;
import org.wso2.carbon.identity.oauth.endpoint.exception.InvalidRequestParentException;
import org.wso2.carbon.identity.oauth.endpoint.message.OAuthMessage;
import org.wso2.carbon.identity.oauth.endpoint.util.EndpointUtil;
import org.wso2.carbon.identity.oauth.endpoint.util.OpenIDConnectUserRPStore;
import org.wso2.carbon.identity.oauth2.IdentityOAuth2Exception;
import org.wso2.carbon.identity.oauth2.IdentityOAuth2ScopeException;
import org.wso2.carbon.identity.oauth2.OAuth2Service;
import org.wso2.carbon.identity.oauth2.RequestObjectException;
import org.wso2.carbon.identity.oauth2.dto.OAuth2AuthorizeReqDTO;
import org.wso2.carbon.identity.oauth2.dto.OAuth2AuthorizeRespDTO;
import org.wso2.carbon.identity.oauth2.dto.OAuth2ClientValidationResponseDTO;
import org.wso2.carbon.identity.oauth2.model.CarbonOAuthAuthzRequest;
import org.wso2.carbon.identity.oauth2.model.HttpRequestHeaderHandler;
import org.wso2.carbon.identity.oauth2.model.OAuth2Parameters;
import org.wso2.carbon.identity.oauth2.token.bindings.TokenBinder;
import org.wso2.carbon.identity.oauth2.util.OAuth2Util;
import org.wso2.carbon.identity.oidc.session.OIDCSessionState;
import org.wso2.carbon.identity.oidc.session.util.OIDCSessionManagementUtil;
import org.wso2.carbon.identity.openidconnect.OIDCConstants;
import org.wso2.carbon.identity.openidconnect.OIDCRequestObjectUtil;
import org.wso2.carbon.identity.openidconnect.OpenIDConnectClaimFilterImpl;
import org.wso2.carbon.identity.openidconnect.model.RequestObject;
import org.wso2.carbon.identity.openidconnect.model.RequestedClaim;
import org.wso2.carbon.registry.core.utils.UUIDGenerator;
import org.wso2.carbon.utils.CarbonUtils;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Scanner;
import java.util.Set;
import java.util.StringJoiner;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import javax.servlet.ServletException;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletRequestWrapper;
import javax.servlet.http.HttpServletResponse;
import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.Response;

import static org.wso2.carbon.identity.application.authentication.endpoint.util.Constants.MANDATORY_CLAIMS;
import static org.wso2.carbon.identity.application.authentication.endpoint.util.Constants.REQUESTED_CLAIMS;
import static org.wso2.carbon.identity.application.authentication.endpoint.util.Constants.USER_CLAIMS_CONSENT_ONLY;
import static org.wso2.carbon.identity.application.authentication.framework.util.FrameworkConstants.REQUEST_PARAM_SP;
import static org.wso2.carbon.identity.application.authentication.framework.util.FrameworkConstants.RequestParams.TENANT_DOMAIN;
import static org.wso2.carbon.identity.oauth.common.OAuthConstants.OAuth20Params.REDIRECT_URI;
import static org.wso2.carbon.identity.oauth.endpoint.state.OAuthAuthorizeState.AUTHENTICATION_RESPONSE;
import static org.wso2.carbon.identity.oauth.endpoint.state.OAuthAuthorizeState.INITIAL_REQUEST;
import static org.wso2.carbon.identity.oauth.endpoint.state.OAuthAuthorizeState.PASSTHROUGH_TO_COMMONAUTH;
import static org.wso2.carbon.identity.oauth.endpoint.state.OAuthAuthorizeState.USER_CONSENT_RESPONSE;
import static org.wso2.carbon.identity.oauth.endpoint.util.EndpointUtil.getErrorPageURL;
import static org.wso2.carbon.identity.oauth.endpoint.util.EndpointUtil.getLoginPageURL;
import static org.wso2.carbon.identity.oauth.endpoint.util.EndpointUtil.getOAuth2Service;
import static org.wso2.carbon.identity.oauth.endpoint.util.EndpointUtil.getOAuthServerConfiguration;
import static org.wso2.carbon.identity.oauth.endpoint.util.EndpointUtil.getSSOConsentService;
import static org.wso2.carbon.identity.oauth.endpoint.util.EndpointUtil.startSuperTenantFlow;
import static org.wso2.carbon.identity.oauth.endpoint.util.EndpointUtil.validateParams;
import static org.wso2.carbon.identity.openidconnect.model.Constants.AUTH_TIME;
import static org.wso2.carbon.identity.openidconnect.model.Constants.DISPLAY;
import static org.wso2.carbon.identity.openidconnect.model.Constants.ID_TOKEN_HINT;
import static org.wso2.carbon.identity.openidconnect.model.Constants.LOGIN_HINT;
import static org.wso2.carbon.identity.openidconnect.model.Constants.MAX_AGE;
import static org.wso2.carbon.identity.openidconnect.model.Constants.NONCE;
import static org.wso2.carbon.identity.openidconnect.model.Constants.PROMPT;
import static org.wso2.carbon.identity.openidconnect.model.Constants.SCOPE;
import static org.wso2.carbon.identity.openidconnect.model.Constants.STATE;

/**
 * Rest implementation of OAuth2 authorize endpoint.
 */
@Path("/authorize")
public class OAuth2AuthzEndpoint {

    private static final Log log = LogFactory.getLog(OAuth2AuthzEndpoint.class);
    private static final String APPROVE = "approve";
    private static final String CONSENT = "consent";
    private static final String AUTHENTICATED_ID_PS = "AuthenticatedIdPs";
    private static final String BEARER = "Bearer";
    private static final String ACR_VALUES = "acr_values";
    private static final String CLAIMS = "claims";
    public static final String COMMA_SEPARATOR = ",";
    public static final String SPACE_SEPARATOR = " ";
    private boolean isCacheAvailable = false;

    private static final String RESPONSE_MODE_FORM_POST = "form_post";
    private static final String RESPONSE_MODE = "response_mode";
    private static final String REQUEST = "request";
    private static final String REQUEST_URI = "request_uri";

    private static final String formPostRedirectPage = getFormPostRedirectPage();
    private static final String DISPLAY_NAME = "DisplayName";
    private static final String ID_TOKEN = "id_token";
    private static final String ACCESS_CODE = "code";
    private static final String DEFAULT_ERROR_DESCRIPTION = "User denied the consent";
    private static final String DEFAULT_ERROR_MSG_FOR_FAILURE = "Authentication required";
    private static final String COMMONAUTH_COOKIE = "commonAuthId";
    private static final String SET_COOKIE_HEADER = "Set-Cookie";
    private static final String REGEX_PATTERN = "regexp";
    private static final String OIDC_SESSION_ID = "OIDC_SESSION_ID";


    private static final String OIDC_DIALECT = "http://wso2.org/oidc/claim";

    private static OpenIDConnectClaimFilterImpl openIDConnectClaimFilter;

    public static OpenIDConnectClaimFilterImpl getOpenIDConnectClaimFilter() {

        return openIDConnectClaimFilter;
    }

    public static void setOpenIDConnectClaimFilter(OpenIDConnectClaimFilterImpl openIDConnectClaimFilter) {

        OAuth2AuthzEndpoint.openIDConnectClaimFilter = openIDConnectClaimFilter;
    }

    @GET
    @Path("/")
    @Consumes("application/x-www-form-urlencoded")
    @Produces("text/html")
    public Response authorize(@Context HttpServletRequest request, @Context HttpServletResponse response)
            throws URISyntaxException, InvalidRequestParentException {

        startSuperTenantFlow();
        OAuthMessage oAuthMessage;

        // TODO: 2021-01-22 Check for the flag in request.
        setCommonAuthIdToRequest(request, response);

        // Using a separate try-catch block as this next try block has operations in the final block.
        try {
            oAuthMessage = buildOAuthMessage(request, response);

        } catch (InvalidRequestParentException e) {
            EndpointUtil.triggerOnAuthzRequestException(e, request);
            throw e;
        }

        try {
            if (isPassthroughToFramework(oAuthMessage)) {
                return handleAuthFlowThroughFramework(oAuthMessage);
            } else if (isInitialRequestFromClient(oAuthMessage)) {
                return handleInitialAuthorizationRequest(oAuthMessage);
            } else if (isAuthenticationResponseFromFramework(oAuthMessage)) {
                return handleAuthenticationResponse(oAuthMessage);
            } else if (isConsentResponseFromUser(oAuthMessage)) {
                return handleResponseFromConsent(oAuthMessage);
            } else {
                return handleInvalidRequest(oAuthMessage);
            }
        } catch (OAuthProblemException e) {
            EndpointUtil.triggerOnAuthzRequestException(e, request);
            return handleOAuthProblemException(oAuthMessage, e);
        } catch (OAuthSystemException e) {
            EndpointUtil.triggerOnAuthzRequestException(e, request);
            return handleOAuthSystemException(oAuthMessage, e);
        } finally {
            handleCachePersistence(oAuthMessage);
            PrivilegedCarbonContext.endTenantFlow();
        }
    }


    private void setCommonAuthIdToRequest(HttpServletRequest request, HttpServletResponse response) {

        // Issue https://github.com/wso2/product-is/issues/11065 needs to addressed.
        response.getHeaders(SET_COOKIE_HEADER).stream()
                .filter(value -> value.contains(COMMONAUTH_COOKIE))
                // TODO: 2021-01-22 Refactor this logic - Check kernel Cookie.
                .map(cookieValue -> cookieValue.split(COMMONAUTH_COOKIE + "=")[1])
                .map(cookieValue -> cookieValue.split(";")[0])
                .findAny().ifPresent(s -> request.setAttribute(COMMONAUTH_COOKIE, s));
    }

    @POST
    @Path("/")
    @Consumes("application/x-www-form-urlencoded")
    @Produces("text/html")
    public Response authorizePost(@Context HttpServletRequest request, @Context HttpServletResponse response,
                                  MultivaluedMap paramMap)
            throws URISyntaxException, InvalidRequestParentException {

        // Validate repeated parameters
        if (!validateParams(request, paramMap)) {
            return Response.status(HttpServletResponse.SC_BAD_REQUEST).location(new URI(getErrorPageURL(request,
                    OAuth2ErrorCodes.INVALID_REQUEST, OAuth2ErrorCodes.OAuth2SubErrorCodes
                            .INVALID_AUTHORIZATION_REQUEST, "Invalid authorization request with repeated parameters",
                    null)))
                    .build();
        }
        HttpServletRequestWrapper httpRequest = new OAuthRequestWrapper(request, paramMap);
        return authorize(httpRequest, response);
    }

    private Response handleInvalidRequest(OAuthMessage oAuthMessage) throws URISyntaxException {

        if (log.isDebugEnabled()) {
            log.debug("Invalid authorization request");
        }

        OAuth2Parameters oAuth2Parameters = getOAuth2ParamsFromOAuthMessage(oAuthMessage);
        return Response.status(HttpServletResponse.SC_FOUND).location(new URI(getErrorPageURL
                (oAuthMessage.getRequest(), OAuth2ErrorCodes.INVALID_REQUEST, OAuth2ErrorCodes.OAuth2SubErrorCodes
                        .INVALID_AUTHORIZATION_REQUEST, "Invalid authorization request", null,
                        oAuth2Parameters))).build();
    }

    private void handleCachePersistence(OAuthMessage oAuthMessage) {

        AuthorizationGrantCacheEntry entry = oAuthMessage.getAuthorizationGrantCacheEntry();
        if (entry != null) {
            AuthorizationGrantCache.getInstance().addToCacheByCode(
                    new AuthorizationGrantCacheKey(entry.getAuthorizationCode()), entry);
        }
    }

    private boolean isConsentResponseFromUser(OAuthMessage oAuthMessage) {

        return USER_CONSENT_RESPONSE.equals(oAuthMessage.getRequestType());
    }

    private boolean isAuthenticationResponseFromFramework(OAuthMessage oAuthMessage) {

        return AUTHENTICATION_RESPONSE.equals(oAuthMessage.getRequestType());
    }

    private boolean isInitialRequestFromClient(OAuthMessage oAuthMessage) {

        return INITIAL_REQUEST.equals(oAuthMessage.getRequestType());
    }

    private boolean isPassthroughToFramework(OAuthMessage oAuthMessage) {

        return PASSTHROUGH_TO_COMMONAUTH.equals(oAuthMessage.getRequestType());
    }

    private OAuthMessage buildOAuthMessage(HttpServletRequest request, HttpServletResponse response)
            throws InvalidRequestParentException {

        return new OAuthMessage.OAuthMessageBuilder()
                .setRequest(request)
                .setResponse(response)
                .build();
    }

    private Response handleOAuthSystemException(OAuthMessage oAuthMessage, OAuthSystemException e)
            throws URISyntaxException {

        OAuth2Parameters params = null;
        if (oAuthMessage.getSessionDataCacheEntry() != null) {
            params = oAuthMessage.getSessionDataCacheEntry().getoAuth2Parameters();
        }
        if (log.isDebugEnabled()) {
            log.debug("Server error occurred while performing authorization", e);
        }
        OAuthProblemException ex = OAuthProblemException.error(OAuth2ErrorCodes.SERVER_ERROR,
                "Server error occurred while performing authorization");
        return Response.status(HttpServletResponse.SC_FOUND).location(new URI(
                EndpointUtil.getErrorRedirectURL(oAuthMessage.getRequest(), ex, params))).build();
    }

    private Response handleOAuthProblemException(OAuthMessage oAuthMessage, OAuthProblemException e) throws
            URISyntaxException {

        if (log.isDebugEnabled()) {
            log.debug(e.getError(), e);
        }

        OAuth2Parameters oAuth2Parameters = getOAuth2ParamsFromOAuthMessage(oAuthMessage);
        String errorPageURL = getErrorPageURL(oAuthMessage.getRequest(), OAuth2ErrorCodes.INVALID_REQUEST,
                OAuth2ErrorCodes.OAuth2SubErrorCodes.UNEXPECTED_SERVER_ERROR, e.getMessage(), null,
                oAuth2Parameters);
        if (OAuthServerConfiguration.getInstance().isRedirectToRequestedRedirectUriEnabled()) {
            return Response.status(HttpServletResponse.SC_FOUND).location(new URI(errorPageURL)).build();

        } else {
            String redirectURI = oAuthMessage.getRequest().getParameter(REDIRECT_URI);

            if (redirectURI != null) {
                try {
                    errorPageURL = errorPageURL + "&" + REDIRECT_URI + "=" + URLEncoder
                            .encode(redirectURI, StandardCharsets.UTF_8.name());
                } catch (UnsupportedEncodingException e1) {
                    if (log.isDebugEnabled()) {
                        log.debug("Error while encoding the error page url", e);
                    }
                }
            }
            return Response.status(HttpServletResponse.SC_FOUND).location(new URI(errorPageURL))
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_FORM_URLENCODED_TYPE).build();
        }
    }

    private static String getFormPostRedirectPage() {

        java.nio.file.Path path = Paths.get(CarbonUtils.getCarbonHome(), "repository", "resources",
                "identity", "pages", "oauth_response.html");
        if (Files.exists(path)) {
            try {
                return new Scanner(Files.newInputStream(path), "UTF-8").useDelimiter("\\A").next();
            } catch (IOException e) {
                if (log.isDebugEnabled()) {
                    log.debug("Failed to find OAuth From post response page in : " + path.toString());
                }
            }
        }
        return null;
    }

    private Response handleResponseFromConsent(OAuthMessage oAuthMessage) throws OAuthSystemException,
            URISyntaxException, ConsentHandlingFailedException {

        updateAuthTimeInSessionDataCacheEntry(oAuthMessage);
        addSessionDataKeyToSessionDataCacheEntry(oAuthMessage);

        String consent = getConsentFromRequest(oAuthMessage);
        if (consent != null) {
            if (OAuthConstants.Consent.DENY.equals(consent)) {
                return handleDeniedConsent(oAuthMessage);
            }

            /*
                Get the user consented claims from the consent response and create a consent receipt.
            */
            handlePostConsent(oAuthMessage);

            OIDCSessionState sessionState = new OIDCSessionState();
            String redirectURL = handleUserConsent(oAuthMessage, consent, sessionState);

            if (isFormPostResponseMode(oAuthMessage, redirectURL)) {
                return handleFormPostResponseMode(oAuthMessage, sessionState, redirectURL);
            }

            redirectURL = manageOIDCSessionState(oAuthMessage, sessionState, redirectURL);
            return Response.status(HttpServletResponse.SC_FOUND).location(new URI(redirectURL)).build();
        } else {
            return handleEmptyConsent(oAuthMessage);
        }
    }

    private boolean isConsentHandlingFromFrameworkSkipped(OAuth2Parameters oAuth2Parameters)
            throws OAuthSystemException {

        ServiceProvider sp = getServiceProvider(oAuth2Parameters.getClientId());
        boolean consentMgtDisabled = isConsentMgtDisabled(sp);
        if (consentMgtDisabled) {
            if (log.isDebugEnabled()) {
                String clientId = oAuth2Parameters.getClientId();
                String spTenantDomain = oAuth2Parameters.getTenantDomain();
                log.debug("Consent Management disabled for client_id: " + clientId + " of tenantDomain: "
                        + spTenantDomain + ". Therefore skipping consent handling for user.");
            }
        }

        return isNotOIDCRequest(oAuth2Parameters) || consentMgtDisabled;
    }

    private boolean isNotOIDCRequest(OAuth2Parameters oAuth2Parameters) {

        return !OAuth2Util.isOIDCAuthzRequest(oAuth2Parameters.getScopes());
    }

    private boolean isConsentMgtDisabled(ServiceProvider sp) {

        return !getSSOConsentService().isSSOConsentManagementEnabled(sp);
    }

    private void handlePostConsent(OAuthMessage oAuthMessage) throws ConsentHandlingFailedException {

        OAuth2Parameters oauth2Params = getOauth2Params(oAuthMessage);
        String tenantDomain = EndpointUtil.getSPTenantDomainFromClientId(oauth2Params.getClientId());
        setSPAttributeToRequest(oAuthMessage.getRequest(), oauth2Params.getApplicationName(), tenantDomain);
        String spTenantDomain = oauth2Params.getTenantDomain();
        AuthenticatedUser loggedInUser = getLoggedInUser(oAuthMessage);
        String clientId = oauth2Params.getClientId();
        ServiceProvider serviceProvider;

        if (log.isDebugEnabled()) {
            log.debug("Initiating post user consent handling for user: " + loggedInUser.toFullQualifiedUsername()
                    + " for client_id: " + clientId + " of tenantDomain: " + spTenantDomain);
        }
        try {
            if (isConsentHandlingFromFrameworkSkipped(oauth2Params)) {
                if (log.isDebugEnabled()) {
                    log.debug(
                            "Consent handling from framework skipped for client_id: " + clientId + " of tenantDomain: "
                                    + spTenantDomain + " for user: " + loggedInUser.toFullQualifiedUsername() + ". " +
                                    "Therefore handling post consent is not applicable.");
                }
                return;
            }

            List<Integer> approvedClaimIds = getUserConsentClaimIds(oAuthMessage);
            serviceProvider = getServiceProvider(clientId);
            /*
                With the current implementation of the SSOConsentService we need to send back the original
                ConsentClaimsData object we got during pre consent stage. Currently we are repeating the API call
                during post consent handling to get the original ConsentClaimsData object (Assuming there is no
                change in SP during pre-consent and post-consent).

                The API on the SSO Consent Service will be improved to avoid having to send the original
                ConsentClaimsData object.
             */
            ConsentClaimsData value = getConsentRequiredClaims(loggedInUser, serviceProvider, oauth2Params);
            /*
                It is needed to pitch the consent required claims with the OIDC claims. otherwise the consent of the
                the claims which are not in the OIDC claims will be saved as consent denied.
            */
            if (value != null) {
                // Remove the claims which dont have values given by the user.
                value.setRequestedClaims(removeConsentRequestedNullUserAttributes(value.getRequestedClaims(),
                        loggedInUser.getUserAttributes(), spTenantDomain));
                List<ClaimMetaData> requestedOidcClaimsList =
                        getRequestedOidcClaimsList(value, oauth2Params, spTenantDomain);
                value.setRequestedClaims(requestedOidcClaimsList);
            }

            // Call framework and create the consent receipt.
            if (log.isDebugEnabled()) {
                log.debug("Creating user consent receipt for user: " + loggedInUser.toFullQualifiedUsername() +
                        " for client_id: " + clientId + " of tenantDomain: " + spTenantDomain);
            }

            if (hasPromptContainsConsent(oauth2Params)) {
                getSSOConsentService().processConsent(approvedClaimIds, serviceProvider,
                        loggedInUser, value, true);
            } else {
                getSSOConsentService().processConsent(approvedClaimIds, serviceProvider,
                        loggedInUser, value, false);
            }

        } catch (OAuthSystemException | SSOConsentServiceException e) {
            String msg = "Error while processing consent of user: " + loggedInUser.toFullQualifiedUsername() + " for " +
                    "client_id: " + clientId + " of tenantDomain: " + spTenantDomain;
            throw new ConsentHandlingFailedException(msg, e);
        } catch (ClaimMetadataException e) {
            throw new ConsentHandlingFailedException("Error while getting claim mappings for " + OIDC_DIALECT, e);
        } catch (RequestObjectException e) {
            throw new ConsentHandlingFailedException("Error while getting essential claims for the session data key " +
                    ": " + oauth2Params.getSessionDataKey(), e);
        }

    }

    private ConsentClaimsData getConsentRequiredClaims(AuthenticatedUser user, ServiceProvider serviceProvider,
                                                       OAuth2Parameters oAuth2Parameters)
            throws SSOConsentServiceException {

        if (hasPromptContainsConsent(oAuth2Parameters)) {
            // Ignore all previous consents and get consent required claims
            return getSSOConsentService().getConsentRequiredClaimsWithoutExistingConsents(serviceProvider, user);
        } else {
            return getSSOConsentService().getConsentRequiredClaimsWithExistingConsents(serviceProvider, user);
        }
    }

    private List<Integer> getUserConsentClaimIds(OAuthMessage oAuthMessage) {

        List<Integer> approvedClaims = new ArrayList<>();
        String consentClaimsPrefix = "consent_";

        Enumeration<String> parameterNames = oAuthMessage.getRequest().getParameterNames();
        while (parameterNames.hasMoreElements()) {
            String parameterName = parameterNames.nextElement();
            if (parameterName.startsWith(consentClaimsPrefix)) {
                try {
                    approvedClaims.add(Integer.parseInt(parameterName.substring(consentClaimsPrefix.length())));
                } catch (NumberFormatException ex) {
                    // Ignore and continue.
                }
            }
        }
        return approvedClaims;
    }

    private void addSessionDataKeyToSessionDataCacheEntry(OAuthMessage oAuthMessage) {

        Cookie cookie = FrameworkUtils.getAuthCookie(oAuthMessage.getRequest());
        if (cookie != null) {
            String sessionContextKey = DigestUtils.sha256Hex(cookie.getValue());
            oAuthMessage.getSessionDataCacheEntry().getParamMap().put(FrameworkConstants.SESSION_DATA_KEY, new String[]
                    {sessionContextKey});
        }
    }

    private String getConsentFromRequest(OAuthMessage oAuthMessage) {

        return oAuthMessage.getRequest().getParameter(CONSENT);
    }

    private Response handleEmptyConsent(OAuthMessage oAuthMessage) throws URISyntaxException {

        String appName = getOauth2Params(oAuthMessage).getApplicationName();

        if (log.isDebugEnabled()) {
            log.debug("Invalid authorization request. \'sessionDataKey\' parameter found but \'consent\' " +
                    "parameter could not be found in request");
        }

        OAuth2Parameters oAuth2Parameters = getOAuth2ParamsFromOAuthMessage(oAuthMessage);
        return Response.status(HttpServletResponse.SC_FOUND).location(new URI(getErrorPageURL(
                oAuthMessage.getRequest(), OAuth2ErrorCodes.INVALID_REQUEST, OAuth2ErrorCodes
                        .OAuth2SubErrorCodes.INVALID_AUTHORIZATION_REQUEST,
                "Invalid authorization request", appName, oAuth2Parameters))).build();
    }

    private String manageOIDCSessionState(OAuthMessage oAuthMessage, OIDCSessionState sessionState,
                                          String redirectURL) {

        OAuth2Parameters oauth2Params = getOauth2Params(oAuthMessage);
        boolean isOIDCRequest = OAuth2Util.isOIDCAuthzRequest(oauth2Params.getScopes());
        if (isOIDCRequest) {
            sessionState.setAddSessionState(true);
            return manageOIDCSessionState(oAuthMessage, sessionState,
                    oauth2Params,
                    getLoggedInUser(oAuthMessage).getAuthenticatedSubjectIdentifier(), redirectURL,
                    oAuthMessage.getSessionDataCacheEntry());
        }
        return redirectURL;
    }

    private Response handleFormPostResponseMode(OAuthMessage oAuthMessage,
                                                OIDCSessionState sessionState, String redirectURL) {

        String authenticatedIdPs = oAuthMessage.getSessionDataCacheEntry().getAuthenticatedIdPs();
        OAuth2Parameters oauth2Params = getOauth2Params(oAuthMessage);
        boolean isOIDCRequest = OAuth2Util.isOIDCAuthzRequest(oauth2Params.getScopes());

        String sessionStateValue = null;
        if (isOIDCRequest) {
            sessionState.setAddSessionState(true);
            sessionStateValue = manageOIDCSessionState(oAuthMessage,
                    sessionState, oauth2Params, getLoggedInUser(oAuthMessage).getAuthenticatedSubjectIdentifier(),
                    redirectURL, oAuthMessage.getSessionDataCacheEntry());
        }

        return Response.ok(createFormPage(redirectURL, oauth2Params.getRedirectURI(),
                authenticatedIdPs, sessionStateValue)).build();
    }

    private Response handleFormPostResponseModeError(OAuthMessage oAuthMessage,
                                                     OAuthProblemException oauthProblemException) {

        OAuth2Parameters oauth2Params = oAuthMessage.getSessionDataCacheEntry().getoAuth2Parameters();

        return Response.ok(createErrorFormPage(oauth2Params.getRedirectURI(), oauthProblemException)).build();
    }

    private Response handleDeniedConsent(OAuthMessage oAuthMessage) throws OAuthSystemException, URISyntaxException {

        OAuth2Parameters oauth2Params = getOauth2Params(oAuthMessage);
        OpenIDConnectUserRPStore.getInstance().putUserRPToStore(getLoggedInUser(oAuthMessage),
                getOauth2Params(oAuthMessage).getApplicationName(), false, oauth2Params.getClientId());

        OAuthErrorDTO oAuthErrorDTO = EndpointUtil.getOAuth2Service().handleUserConsentDenial(oauth2Params);
        OAuthProblemException ex = buildConsentDenialException(oAuthErrorDTO);

        String denyResponse = EndpointUtil.getErrorRedirectURL(oAuthMessage.getRequest(), ex, oauth2Params);
        if (StringUtils.equals(oauth2Params.getResponseMode(), RESPONSE_MODE_FORM_POST)) {
            return handleFailedState(oAuthMessage, oauth2Params, ex);
        }
        return Response.status(HttpServletResponse.SC_FOUND).location(new URI(denyResponse)).build();
    }

    private OAuthProblemException buildConsentDenialException(OAuthErrorDTO oAuthErrorDTO) {

        String errorDescription = DEFAULT_ERROR_DESCRIPTION;

        // Adding custom error description.
        if (oAuthErrorDTO != null && StringUtils.isNotBlank(oAuthErrorDTO.getErrorDescription())) {
            errorDescription = oAuthErrorDTO.getErrorDescription();
        }

        OAuthProblemException error = OAuthProblemException.error(OAuth2ErrorCodes.ACCESS_DENIED, errorDescription);

        // Adding Error URI if exist.
        if (oAuthErrorDTO != null && StringUtils.isNotBlank(oAuthErrorDTO.getErrorURI())) {
            error.uri(oAuthErrorDTO.getErrorURI());
        }
        return error;
    }

    private Response handleAuthenticationResponse(OAuthMessage oAuthMessage)
            throws OAuthSystemException, URISyntaxException, ConsentHandlingFailedException {

        updateAuthTimeInSessionDataCacheEntry(oAuthMessage);
        addSessionDataKeyToSessionDataCacheEntry(oAuthMessage);

        OAuth2Parameters oauth2Params = getOauth2Params(oAuthMessage);
        String tenantDomain = EndpointUtil.getSPTenantDomainFromClientId(oauth2Params.getClientId());
        setSPAttributeToRequest(oAuthMessage.getRequest(), oauth2Params.getApplicationName(), tenantDomain);
        String sessionDataKeyFromLogin = getSessionDataKeyFromLogin(oAuthMessage);
        AuthenticationResult authnResult = getAuthenticationResult(oAuthMessage, sessionDataKeyFromLogin);

        if (isAuthnResultFound(authnResult)) {
            removeAuthenticationResult(oAuthMessage, sessionDataKeyFromLogin);

            if (authnResult.isAuthenticated()) {
                return handleSuccessfulAuthentication(oAuthMessage, oauth2Params, authnResult);
            } else {
                return handleFailedAuthentication(oAuthMessage, oauth2Params, authnResult);
            }
        } else {
            return handleEmptyAuthenticationResult(oAuthMessage);
        }
    }

    private boolean isAuthnResultFound(AuthenticationResult authnResult) {

        return authnResult != null;
    }

    private Response handleSuccessfulAuthentication(OAuthMessage oAuthMessage, OAuth2Parameters oauth2Params,
                                                    AuthenticationResult authenticationResult)
            throws OAuthSystemException, URISyntaxException, ConsentHandlingFailedException {

        boolean isOIDCRequest = OAuth2Util.isOIDCAuthzRequest(oauth2Params.getScopes());
        AuthenticatedUser authenticatedUser = authenticationResult.getSubject();
        if (authenticatedUser.getUserAttributes() != null) {
            authenticatedUser.setUserAttributes(new ConcurrentHashMap<>(authenticatedUser.getUserAttributes()));
        }

        addToAuthenticationResultDetailsToOAuthMessage(oAuthMessage, authenticationResult, authenticatedUser);

        OIDCSessionState sessionState = new OIDCSessionState();
        String redirectURL = null;
        try {
            redirectURL = doUserAuthorization(oAuthMessage, oAuthMessage.getSessionDataKeyFromLogin(), sessionState);
        } catch (OAuthProblemException ex) {
            if (StringUtils.equals(oauth2Params.getResponseMode(), RESPONSE_MODE_FORM_POST)) {
                return handleFailedState(oAuthMessage, oauth2Params, ex);
            } else {
                redirectURL = EndpointUtil.getErrorRedirectURL(ex, oauth2Params);
            }
        }

        if (isFormPostResponseMode(oAuthMessage, redirectURL)) {
            return handleFormPostMode(oAuthMessage, oauth2Params, redirectURL, isOIDCRequest, sessionState);
        }

        if (isOIDCRequest) {
            redirectURL = manageOIDCSessionState(oAuthMessage,
                    sessionState, oauth2Params, authenticatedUser.getAuthenticatedSubjectIdentifier(),
                    redirectURL, oAuthMessage.getSessionDataCacheEntry());
        }

        return Response.status(HttpServletResponse.SC_FOUND).location(new URI(redirectURL)).build();
    }

    private String getSessionDataKeyFromLogin(OAuthMessage oAuthMessage) {

        return oAuthMessage.getSessionDataKeyFromLogin();
    }

    private Response handleFailedState(OAuthMessage oAuthMessage, OAuth2Parameters oauth2Params,
                                       OAuthProblemException oauthException) throws URISyntaxException {

        String redirectURL = EndpointUtil.getErrorRedirectURL(oauthException, oauth2Params);
        if (StringUtils.equals(oauth2Params.getResponseMode(), RESPONSE_MODE_FORM_POST)) {
            return handleFormPostResponseModeError(oAuthMessage, oauthException);
        } else {
            return Response.status(HttpServletResponse.SC_FOUND).location(new URI(redirectURL)).build();
        }
    }

    private Response handleFailedAuthentication(OAuthMessage oAuthMessage, OAuth2Parameters oauth2Params,
                                                AuthenticationResult authnResult) throws URISyntaxException {

        OAuthErrorDTO oAuthErrorDTO = EndpointUtil.getOAuth2Service().handleAuthenticationFailure(oauth2Params);
        OAuthProblemException oauthException = buildOAuthProblemException(authnResult, oAuthErrorDTO);
        return handleFailedState(oAuthMessage, oauth2Params, oauthException);
    }

    private Response handleEmptyAuthenticationResult(OAuthMessage oAuthMessage) throws URISyntaxException {

        String appName = getOauth2Params(oAuthMessage).getApplicationName();

        if (log.isDebugEnabled()) {
            log.debug("Invalid authorization request. \'sessionDataKey\' attribute found but " +
                    "corresponding AuthenticationResult does not exist in the cache.");
        }

        OAuth2Parameters oAuth2Parameters = getOAuth2ParamsFromOAuthMessage(oAuthMessage);
        return Response.status(HttpServletResponse.SC_FOUND).location(new URI(
                getErrorPageURL(oAuthMessage.getRequest(), OAuth2ErrorCodes.INVALID_REQUEST, OAuth2ErrorCodes
                        .OAuth2SubErrorCodes.INVALID_AUTHORIZATION_REQUEST, "Invalid authorization request", appName,
                        oAuth2Parameters)
        )).build();
    }

    private Response handleFormPostMode(OAuthMessage oAuthMessage, OAuth2Parameters oauth2Params, String redirectURL,
                                        boolean isOIDCRequest, OIDCSessionState sessionState) {

        String sessionStateValue = null;
        if (isOIDCRequest) {
            sessionState.setAddSessionState(true);
            sessionStateValue = manageOIDCSessionState(oAuthMessage,
                    sessionState,
                    oauth2Params,
                    getLoggedInUser(oAuthMessage).getAuthenticatedSubjectIdentifier(),
                    redirectURL, oAuthMessage.getSessionDataCacheEntry());
        }

        return Response.ok(createFormPage(redirectURL, oauth2Params.getRedirectURI(),
                StringUtils.EMPTY, sessionStateValue)).build();
    }

    private void addToAuthenticationResultDetailsToOAuthMessage(OAuthMessage oAuthMessage,
                AuthenticationResult authnResult, AuthenticatedUser authenticatedUser) {

        oAuthMessage.getSessionDataCacheEntry().setLoggedInUser(authenticatedUser);
        oAuthMessage.getSessionDataCacheEntry().setAuthenticatedIdPs(authnResult.getAuthenticatedIdPs());
        oAuthMessage.getSessionDataCacheEntry().setSessionContextIdentifier((String)
                authnResult.getProperty(FrameworkConstants.AnalyticsAttributes.SESSION_ID));
    }

    private void updateAuthTimeInSessionDataCacheEntry(OAuthMessage oAuthMessage) {

        Cookie cookie = FrameworkUtils.getAuthCookie(oAuthMessage.getRequest());
        long authTime = getAuthenticatedTimeFromCommonAuthCookie(cookie,
                oAuthMessage.getSessionDataCacheEntry().getoAuth2Parameters().getLoginTenantDomain());

        if (authTime > 0) {
            oAuthMessage.getSessionDataCacheEntry().setAuthTime(authTime);
        }

        associateAuthenticationHistory(oAuthMessage.getSessionDataCacheEntry(), cookie);
    }

    private boolean isFormPostResponseMode(OAuthMessage oAuthMessage, String redirectURL) {

        OAuth2Parameters oauth2Params = getOauth2Params(oAuthMessage);
        return isFormPostResponseMode(oauth2Params, redirectURL);
    }

    private boolean isFormPostResponseMode(OAuth2Parameters oauth2Params, String redirectURL) {

        return RESPONSE_MODE_FORM_POST.equals(oauth2Params.getResponseMode()) && isJSON(redirectURL);
    }

    private Response handleInitialAuthorizationRequest(OAuthMessage oAuthMessage) throws OAuthSystemException,
            OAuthProblemException, URISyntaxException, InvalidRequestParentException {

        String redirectURL = handleOAuthAuthorizationRequest(oAuthMessage);
        String type = getRequestProtocolType(oAuthMessage);

        if (AuthenticatorFlowStatus.SUCCESS_COMPLETED == oAuthMessage.getFlowStatus()) {
            return handleAuthFlowThroughFramework(oAuthMessage, type);
        } else {
            return Response.status(HttpServletResponse.SC_FOUND).location(new URI(redirectURL)).build();
        }
    }

    private String getRequestProtocolType(OAuthMessage oAuthMessage) {

        String type = OAuthConstants.Scope.OAUTH2;
        String scopes = oAuthMessage.getRequest().getParameter(OAuthConstants.OAuth10AParams.SCOPE);
        if (scopes != null && scopes.contains(OAuthConstants.Scope.OPENID)) {
            type = OAuthConstants.Scope.OIDC;
        }
        return type;
    }

    private boolean isJSON(String redirectURL) {

        try {
            new JSONObject(redirectURL);
        } catch (JSONException ex) {
            return false;
        }
        return true;
    }

    private String createBaseFormPage(String params, String redirectURI) {

        if (StringUtils.isNotBlank(formPostRedirectPage)) {
            String newPage = formPostRedirectPage;
            String pageWithRedirectURI = newPage.replace("$redirectURI", redirectURI);
            return pageWithRedirectURI.replace("<!--$params-->", params);
        }

        String formHead = "<html>\n" +
                "   <head><title>Submit This Form</title></head>\n" +
                "   <body onload=\"javascript:document.forms[0].submit()\">\n" +
                "    <p>Click the submit button if automatic redirection failed.</p>" +
                "    <form method=\"post\" action=\"" + redirectURI + "\">\n";

        String formBottom = "<input type=\"submit\" value=\"Submit\">" +
                "</form>\n" +
                "</body>\n" +
                "</html>";

        StringBuilder form = new StringBuilder(formHead);
        form.append(params);
        form.append(formBottom);
        return form.toString();
    }

    private String createFormPage(String jsonPayLoad, String redirectURI, String authenticatedIdPs,
                                  String sessionStateValue) {

        String params = buildParams(jsonPayLoad, authenticatedIdPs, sessionStateValue);
        return createBaseFormPage(params, redirectURI);
    }

    private String createErrorFormPage(String redirectURI, OAuthProblemException oauthProblemException) {

        String params = buildErrorParams(oauthProblemException);
        return createBaseFormPage(params, redirectURI);
    }

    private String buildParams(String jsonPayLoad, String authenticatedIdPs, String sessionStateValue) {

        JSONObject jsonObject = new JSONObject(jsonPayLoad);
        StringBuilder paramStringBuilder = new StringBuilder();

        for (Object key : jsonObject.keySet()) {
            paramStringBuilder.append("<input type=\"hidden\" name=\"")
                    .append(key)
                    .append("\"" + "value=\"")
                    .append(jsonObject.get(key.toString()))
                    .append("\"/>\n");
        }

        if (authenticatedIdPs != null && !authenticatedIdPs.isEmpty()) {
            paramStringBuilder.append("<input type=\"hidden\" name=\"AuthenticatedIdPs\" value=\"")
                    .append(authenticatedIdPs)
                    .append("\"/>\n");
        }

        if (sessionStateValue != null && !sessionStateValue.isEmpty()) {
            paramStringBuilder.append("<input type=\"hidden\" name=\"session_state\" value=\"")
                    .append(sessionStateValue)
                    .append("\"/>\n");
        }
        return paramStringBuilder.toString();
    }

    private String buildErrorParams(OAuthProblemException oauthProblemException) {

        StringBuilder paramStringBuilder = new StringBuilder();

        if (StringUtils.isNotEmpty(oauthProblemException.getError())) {
            paramStringBuilder.append("<input type=\"hidden\" name=\"error\" value=\"")
                    .append(oauthProblemException.getError())
                    .append("\"/>\n");
        }

        if (StringUtils.isNotEmpty(oauthProblemException.getDescription())) {
            paramStringBuilder.append("<input type=\"hidden\" name=\"error_description\" value=\"")
                    .append(oauthProblemException.getDescription())
                    .append("\"/>\n");
        }

        return paramStringBuilder.toString();
    }

    private void removeAuthenticationResult(OAuthMessage oAuthMessage, String sessionDataKey) {

        if (isCacheAvailable) {
            FrameworkUtils.removeAuthenticationResultFromCache(sessionDataKey);
        } else {
            oAuthMessage.getRequest().removeAttribute(FrameworkConstants.RequestAttribute.AUTH_RESULT);
        }
    }

    private String handleUserConsent(OAuthMessage oAuthMessage, String consent, OIDCSessionState sessionState)
            throws OAuthSystemException {

        OAuth2Parameters oauth2Params = getOauth2Params(oAuthMessage);
        storeUserConsent(oAuthMessage, consent);
        OAuthResponse oauthResponse;
        String responseType = oauth2Params.getResponseType();
        HttpRequestHeaderHandler httpRequestHeaderHandler = new HttpRequestHeaderHandler(oAuthMessage.getRequest());
        // authorizing the request
        OAuth2AuthorizeRespDTO authzRespDTO =
                authorize(oauth2Params, oAuthMessage.getSessionDataCacheEntry(), httpRequestHeaderHandler);

        if (isSuccessfulAuthorization(authzRespDTO)) {
            oauthResponse =
                    handleSuccessAuthorization(oAuthMessage, sessionState, oauth2Params, responseType, authzRespDTO);
        } else if (isFailureAuthorizationWithErorrCode(authzRespDTO)) {
            // Authorization failure due to various reasons
            return handleFailureAuthorization(oAuthMessage, sessionState, oauth2Params, authzRespDTO);
        } else {
            // Authorization failure due to various reasons
            return handleServerErrorAuthorization(oAuthMessage, sessionState, oauth2Params);
        }

        //When response_mode equals to form_post, body parameter is passed back.
        if (isFormPostModeAndResponseBodyExists(oauth2Params, oauthResponse)) {
            return oauthResponse.getBody();
        } else {
            // When responseType contains "id_token", the resulting token is passed back as a URI fragment
            // as per the specification: http://openid.net/specs/openid-connect-core-1_0.html#HybridCallback
            if (hasIDTokenInResponseType(responseType)) {
                return buildOIDCResponseWithURIFragment(oauthResponse, authzRespDTO);
            } else {
                return appendAuthenticatedIDPs(oAuthMessage.getSessionDataCacheEntry(), oauthResponse.getLocationUri());
            }
        }
    }

    private boolean hasIDTokenInResponseType(String responseType) {

        return StringUtils.isNotBlank(responseType) && responseType.toLowerCase().contains(OAuthConstants.ID_TOKEN);
    }

    private String buildOIDCResponseWithURIFragment(OAuthResponse oauthResponse, OAuth2AuthorizeRespDTO authzRespDTO) {

        if (authzRespDTO.getCallbackURI().contains("?")) {
            return authzRespDTO.getCallbackURI() + "#" + StringUtils.substring(oauthResponse.getLocationUri()
                    , authzRespDTO.getCallbackURI().length() + 1);
        } else {
            return oauthResponse.getLocationUri().replace("?", "#");
        }
    }

    private boolean isFailureAuthorizationWithErorrCode(OAuth2AuthorizeRespDTO authzRespDTO) {

        return authzRespDTO != null && authzRespDTO.getErrorCode() != null;
    }

    private boolean isSuccessfulAuthorization(OAuth2AuthorizeRespDTO authzRespDTO) {

        return authzRespDTO != null && authzRespDTO.getErrorCode() == null;
    }

    private void storeUserConsent(OAuthMessage oAuthMessage, String consent) throws OAuthSystemException {

        OAuth2Parameters oauth2Params = getOauth2Params(oAuthMessage);
        String applicationName = oauth2Params.getApplicationName();
        AuthenticatedUser loggedInUser = getLoggedInUser(oAuthMessage);
        String clientId = oauth2Params.getClientId();

        ServiceProvider serviceProvider = getServiceProvider(oauth2Params.getClientId());

        if (!isConsentSkipped(serviceProvider)) {
            boolean approvedAlways = OAuthConstants.Consent.APPROVE_ALWAYS.equals(consent);
            if (approvedAlways) {
                OpenIDConnectUserRPStore.getInstance().putUserRPToStore(loggedInUser, applicationName,
                        true, clientId);
                if (hasPromptContainsConsent(oauth2Params)) {
                    EndpointUtil.storeOAuthScopeConsent(loggedInUser, oauth2Params, true);
                } else {
                    EndpointUtil.storeOAuthScopeConsent(loggedInUser, oauth2Params, false);
                }
            }
        }
    }

    private boolean isFormPostModeAndResponseBodyExists(OAuth2Parameters oauth2Params, OAuthResponse oauthResponse) {

        return RESPONSE_MODE_FORM_POST.equals(oauth2Params.getResponseMode())
                && StringUtils.isNotEmpty(oauthResponse.getBody());
    }

    private String handleServerErrorAuthorization(OAuthMessage oAuthMessage, OIDCSessionState sessionState,
                                                  OAuth2Parameters
                                                          oauth2Params) {

        sessionState.setAuthenticated(false);
        String errorCode = OAuth2ErrorCodes.SERVER_ERROR;
        String errorMsg = "Error occurred while processing the request";
        OAuthProblemException oauthProblemException = OAuthProblemException.error(
                errorCode, errorMsg);
        return EndpointUtil.getErrorRedirectURL(oAuthMessage.getRequest(), oauthProblemException, oauth2Params);
    }

    private String handleFailureAuthorization(OAuthMessage oAuthMessage, OIDCSessionState sessionState,
                                              OAuth2Parameters oauth2Params,
                                              OAuth2AuthorizeRespDTO authzRespDTO) {

        sessionState.setAuthenticated(false);
        String errorMsg;
        if (authzRespDTO.getErrorMsg() != null) {
            errorMsg = authzRespDTO.getErrorMsg();
        } else {
            errorMsg = "Error occurred while processing the request";
        }
        OAuthProblemException oauthProblemException = OAuthProblemException.error(
                authzRespDTO.getErrorCode(), errorMsg);
        return EndpointUtil.getErrorRedirectURL(oAuthMessage.getRequest(), oauthProblemException, oauth2Params);
    }

    private OAuthResponse handleSuccessAuthorization(OAuthMessage oAuthMessage, OIDCSessionState sessionState,
                                                     OAuth2Parameters oauth2Params, String responseType,
                                                     OAuth2AuthorizeRespDTO authzRespDTO) throws OAuthSystemException {

        OAuthASResponse.OAuthAuthorizationResponseBuilder builder = OAuthASResponse.authorizationResponse(
                oAuthMessage.getRequest(), HttpServletResponse.SC_FOUND);
        // all went okay
        if (isAuthorizationCodeExists(authzRespDTO)) {
            // Get token binder if it is enabled for the client.
            Optional<TokenBinder> tokenBinderOptional = getTokenBinder(oauth2Params.getClientId());
            String tokenBindingValue = null;
            if (tokenBinderOptional.isPresent()) {
                TokenBinder tokenBinder = tokenBinderOptional.get();
                tokenBindingValue = tokenBinder.getOrGenerateTokenBindingValue(oAuthMessage.getRequest());
                tokenBinder.setTokenBindingValueForResponse(oAuthMessage.getResponse(), tokenBindingValue);
            }
            setAuthorizationCode(oAuthMessage, authzRespDTO, builder, tokenBindingValue);
        }
        if (isResponseTypeNotIdTokenOrNone(responseType, authzRespDTO)) {
            setAccessToken(authzRespDTO, builder);
            setScopes(authzRespDTO, builder);
        }
        if (isIdTokenExists(authzRespDTO)) {
            setIdToken(authzRespDTO, builder);
            oAuthMessage.setProperty(OIDC_SESSION_ID, authzRespDTO.getOidcSessionId());
        }
        if (StringUtils.isNotBlank(oauth2Params.getState())) {
            builder.setParam(OAuth.OAUTH_STATE, oauth2Params.getState());
        }
        String redirectURL = authzRespDTO.getCallbackURI();

        OAuthResponse oauthResponse;

        if (RESPONSE_MODE_FORM_POST.equals(oauth2Params.getResponseMode())) {
            oauthResponse = handleFormPostMode(oAuthMessage, builder, redirectURL);
        } else {
            oauthResponse = builder.location(redirectURL).buildQueryMessage();
        }

        sessionState.setAuthenticated(true);
        return oauthResponse;
    }

    private Optional<TokenBinder> getTokenBinder(String clientId) throws OAuthSystemException {

        OAuthAppDO oAuthAppDO;
        try {
            oAuthAppDO = OAuth2Util.getAppInformationByClientId(clientId);
        } catch (IdentityOAuth2Exception | InvalidOAuthClientException e) {
            throw new OAuthSystemException("Failed to retrieve OAuth application with client id: " + clientId, e);
        }

        if (oAuthAppDO == null || StringUtils.isBlank(oAuthAppDO.getTokenBindingType())) {
            return Optional.empty();
        }

        OAuth2Service oAuth2Service = getOAuth2Service();
        List<TokenBinder> supportedTokenBinders = oAuth2Service.getSupportedTokenBinders();
        if (supportedTokenBinders == null || supportedTokenBinders.isEmpty()) {
            return Optional.empty();
        }

        return supportedTokenBinders.stream().filter(t -> t.getBindingType().equals(oAuthAppDO.getTokenBindingType()))
                .findAny();
    }

    private OAuthResponse handleFormPostMode(OAuthMessage oAuthMessage,
                                             OAuthASResponse.OAuthAuthorizationResponseBuilder builder,
                                             String redirectURL) throws OAuthSystemException {

        OAuthResponse oauthResponse;
        String authenticatedIdPs = oAuthMessage.getSessionDataCacheEntry().getAuthenticatedIdPs();
        if (authenticatedIdPs != null && !authenticatedIdPs.isEmpty()) {
            builder.setParam(AUTHENTICATED_ID_PS, oAuthMessage.getSessionDataCacheEntry().getAuthenticatedIdPs());
        }
        oauthResponse = builder.location(redirectURL).buildJSONMessage();
        return oauthResponse;
    }

    private boolean isIdTokenExists(OAuth2AuthorizeRespDTO authzRespDTO) {

        return StringUtils.isNotBlank(authzRespDTO.getIdToken());
    }

    private boolean isResponseTypeNotIdTokenOrNone(String responseType, OAuth2AuthorizeRespDTO authzRespDTO) {

        return StringUtils.isNotBlank(authzRespDTO.getAccessToken()) &&
                !OAuthConstants.ID_TOKEN.equalsIgnoreCase(responseType) &&
                !OAuthConstants.NONE.equalsIgnoreCase(responseType);
    }

    private boolean isAuthorizationCodeExists(OAuth2AuthorizeRespDTO authzRespDTO) {

        return StringUtils.isNotBlank(authzRespDTO.getAuthorizationCode());
    }

    private void setIdToken(OAuth2AuthorizeRespDTO authzRespDTO,
                            OAuthASResponse.OAuthAuthorizationResponseBuilder builder) {

        builder.setParam(OAuthConstants.ID_TOKEN, authzRespDTO.getIdToken());
    }

    private void setAuthorizationCode(OAuthMessage oAuthMessage, OAuth2AuthorizeRespDTO authzRespDTO,
                                      OAuthASResponse.OAuthAuthorizationResponseBuilder builder,
                                      String tokenBindingValue) throws OAuthSystemException {

        builder.setCode(authzRespDTO.getAuthorizationCode());
        addUserAttributesToOAuthMessage(oAuthMessage, authzRespDTO.getAuthorizationCode(), authzRespDTO.getCodeId(),
                tokenBindingValue);
    }

    private void setAccessToken(OAuth2AuthorizeRespDTO authzRespDTO,
                                OAuthASResponse.OAuthAuthorizationResponseBuilder builder) {

        builder.setAccessToken(authzRespDTO.getAccessToken());
        builder.setExpiresIn(authzRespDTO.getValidityPeriod());
        builder.setParam(OAuth.OAUTH_TOKEN_TYPE, BEARER);
    }

    private void setScopes(OAuth2AuthorizeRespDTO authzRespDTO,
                           OAuthASResponse.OAuthAuthorizationResponseBuilder builder) {

        String[] scopes = authzRespDTO.getScope();
        if (scopes != null && scopes.length > 0) {
            String scopeString =  StringUtils.join(scopes, " ");
            builder.setScope(scopeString.trim());
        }
    }

    private void addUserAttributesToOAuthMessage(OAuthMessage oAuthMessage, String code, String codeId,
                                                 String tokenBindingValue) throws OAuthSystemException {

        SessionDataCacheEntry sessionDataCacheEntry = oAuthMessage.getSessionDataCacheEntry();
        AuthorizationGrantCacheEntry authorizationGrantCacheEntry = new AuthorizationGrantCacheEntry(
                sessionDataCacheEntry.getLoggedInUser().getUserAttributes());

        ClaimMapping key = new ClaimMapping();
        Claim claimOfKey = new Claim();
        claimOfKey.setClaimUri(OAuth2Util.SUB);
        key.setRemoteClaim(claimOfKey);
        String sub = sessionDataCacheEntry.getLoggedInUser().getUserAttributes().get(key);

        if (StringUtils.isBlank(sub)) {
            sub = sessionDataCacheEntry.getLoggedInUser().getAuthenticatedSubjectIdentifier();
        }
        if (StringUtils.isNotBlank(sub)) {
            if (log.isDebugEnabled() && IdentityUtil.isTokenLoggable(IdentityConstants.IdentityTokens.USER_CLAIMS)) {
                log.debug("Setting subject: " + sub + " as the sub claim in cache against the authorization code.");
            }
            authorizationGrantCacheEntry.setSubjectClaim(sub);
        }
        //PKCE
        String[] pkceCodeChallengeArray = sessionDataCacheEntry.getParamMap().get(
                OAuthConstants.OAUTH_PKCE_CODE_CHALLENGE);
        String[] pkceCodeChallengeMethodArray = sessionDataCacheEntry.getParamMap().get(
                OAuthConstants.OAUTH_PKCE_CODE_CHALLENGE_METHOD);
        String pkceCodeChallenge = null;
        String pkceCodeChallengeMethod = null;

        if (ArrayUtils.isNotEmpty(pkceCodeChallengeArray)) {
            pkceCodeChallenge = pkceCodeChallengeArray[0];
        }
        if (ArrayUtils.isNotEmpty(pkceCodeChallengeMethodArray)) {
            pkceCodeChallengeMethod = pkceCodeChallengeMethodArray[0];
        }
        authorizationGrantCacheEntry.setAcrValue(sessionDataCacheEntry.getoAuth2Parameters().getACRValues());
        authorizationGrantCacheEntry.setNonceValue(sessionDataCacheEntry.getoAuth2Parameters().getNonce());
        authorizationGrantCacheEntry.setCodeId(codeId);
        authorizationGrantCacheEntry.setPkceCodeChallenge(pkceCodeChallenge);
        authorizationGrantCacheEntry.setPkceCodeChallengeMethod(pkceCodeChallengeMethod);
        authorizationGrantCacheEntry.setEssentialClaims(
                sessionDataCacheEntry.getoAuth2Parameters().getEssentialClaims());
        authorizationGrantCacheEntry.setAuthTime(sessionDataCacheEntry.getAuthTime());
        authorizationGrantCacheEntry.setMaxAge(sessionDataCacheEntry.getoAuth2Parameters().getMaxAge());
        authorizationGrantCacheEntry.setTokenBindingValue(tokenBindingValue);
        authorizationGrantCacheEntry.setSessionContextIdentifier(sessionDataCacheEntry.getSessionContextIdentifier());
        String[] sessionIds = sessionDataCacheEntry.getParamMap().get(FrameworkConstants.SESSION_DATA_KEY);
        if (ArrayUtils.isNotEmpty(sessionIds)) {
            String commonAuthSessionId = sessionIds[0];
            SessionContext sessionContext = FrameworkUtils.getSessionContextFromCache(commonAuthSessionId,
                    sessionDataCacheEntry.getoAuth2Parameters().getLoginTenantDomain());
            if (sessionContext != null) {
                String selectedAcr = sessionContext.getSessionAuthHistory().getSelectedAcrValue();
                authorizationGrantCacheEntry.setSelectedAcrValue(selectedAcr);
            }
        }

        String[] amrEntries = sessionDataCacheEntry.getParamMap().get(OAuthConstants.AMR);
        if (amrEntries != null) {
            for (String amrEntry : amrEntries) {
                authorizationGrantCacheEntry.addAmr(amrEntry);
            }
        }
        authorizationGrantCacheEntry.setAuthorizationCode(code);
        boolean isRequestObjectFlow = sessionDataCacheEntry.getoAuth2Parameters().isRequestObjectFlow();
        authorizationGrantCacheEntry.setRequestObjectFlow(isRequestObjectFlow);
        oAuthMessage.setAuthorizationGrantCacheEntry(authorizationGrantCacheEntry);
    }

    /**
     * http://tools.ietf.org/html/rfc6749#section-4.1.2
     * <p/>
     * 4.1.2.1. Error Response
     * <p/>
     * If the request fails due to a missing, invalid, or mismatching
     * redirection URI, or if the client identifier is missing or invalid,
     * the authorization server SHOULD inform the resource owner of the
     * error and MUST NOT automatically redirect the user-agent to the
     * invalid redirection URI.
     * <p/>
     * If the resource owner denies the access request or if the request
     * fails for reasons other than a missing or invalid redirection URI,
     * the authorization server informs the client by adding the following
     * parameters to the query component of the redirection URI using the
     * "application/x-www-form-urlencoded" format
     *
     * @param oAuthMessage oAuthMessage
     * @return String redirectURL
     * @throws OAuthSystemException  OAuthSystemException
     * @throws OAuthProblemException OAuthProblemException
     */
    private String handleOAuthAuthorizationRequest(OAuthMessage oAuthMessage)
            throws OAuthSystemException, OAuthProblemException, InvalidRequestException {

        OAuth2ClientValidationResponseDTO validationResponse = validateClient(oAuthMessage);

        if (!validationResponse.isValidClient()) {
            EndpointUtil.triggerOnRequestValidationFailure(oAuthMessage, validationResponse);
            return getErrorPageURL(oAuthMessage.getRequest(), validationResponse.getErrorCode(), OAuth2ErrorCodes
                    .OAuth2SubErrorCodes.INVALID_CLIENT, validationResponse.getErrorMsg(), null);
        } else {
            String tenantDomain = EndpointUtil.getSPTenantDomainFromClientId(oAuthMessage.getClientId());
            setSPAttributeToRequest(oAuthMessage.getRequest(), validationResponse.getApplicationName(), tenantDomain);
        }

        OAuthAuthzRequest oauthRequest = new CarbonOAuthAuthzRequest(oAuthMessage.getRequest());

        OAuth2Parameters params = new OAuth2Parameters();
        String sessionDataKey = UUIDGenerator.generateUUID();
        params.setSessionDataKey(sessionDataKey);
        String redirectURI = populateOauthParameters(params, oAuthMessage, validationResponse, oauthRequest);
        if (redirectURI != null) {
            return redirectURI;
        }

        String prompt = oauthRequest.getParam(OAuthConstants.OAuth20Params.PROMPT);
        params.setPrompt(prompt);

        redirectURI = analyzePromptParameter(oAuthMessage, params, prompt);
        if (redirectURI != null) {
            return redirectURI;
        }

        if (isNonceMandatory(params.getResponseType())) {
            validateNonceParameter(params.getNonce());
        }

        addDataToSessionCache(oAuthMessage, params, sessionDataKey);

        try {
            oAuthMessage.getRequest().setAttribute(FrameworkConstants.RequestParams.FLOW_STATUS, AuthenticatorFlowStatus
                    .SUCCESS_COMPLETED);
            oAuthMessage.getRequest().setAttribute(FrameworkConstants.SESSION_DATA_KEY, sessionDataKey);
            return getLoginPageURL(oAuthMessage.getClientId(), sessionDataKey, oAuthMessage.isForceAuthenticate(),
                    oAuthMessage.isPassiveAuthentication(), oauthRequest.getScopes(),
                    oAuthMessage.getRequest().getParameterMap(), oAuthMessage.getRequest());
        } catch (IdentityOAuth2Exception e) {
            return handleException(e);
        }
    }

    /**
     * Checks whether the given authentication flow requires {@code nonce} as a mandatory parameter.
     *
     * @param responseType Response type from the authentication request.
     * @return {@true} {@code true} if parameter is mandatory, {@code false} if not.
     */
    private boolean isNonceMandatory(String responseType) {

        /*
        nonce parameter is required for the OIDC hybrid flow and implicit flow grant types requesting ID_TOKEN.
         */
        return Arrays.stream(responseType.split("\\s+")).anyMatch(OAuthConstants.ID_TOKEN::equals);
    }

    /**
     * Validates the nonce parameter as mandatory.
     *
     * @param nonce {@code nonce} parameter. Presence of nonce in the request object is honoured over
     *              oauth2 request parameters.
     * @throws OAuthProblemException Nonce parameter is not found.
     */
    private void validateNonceParameter(String nonce) throws OAuthProblemException {

        if (StringUtils.isBlank(nonce)) {
            throw OAuthProblemException.error(OAuthError.TokenResponse.INVALID_REQUEST)
                    .description("\'response_type\' contains \'id_token\'; but \'nonce\' parameter not found");
        }
        if (log.isDebugEnabled()) {
            log.debug("Mandatory " + NONCE + " parameter is successfully validated");
        }
    }

    private void persistRequestObject(OAuth2Parameters params, RequestObject requestObject)
            throws RequestObjectException {

        String sessionDataKey = params.getSessionDataKey();
        if (EndpointUtil.getRequestObjectService() != null) {
            if (requestObject != null && MapUtils.isNotEmpty(requestObject.getRequestedClaims())) {
                EndpointUtil.getRequestObjectService().addRequestObject(params.getClientId(), sessionDataKey,
                        new ArrayList(requestObject.getRequestedClaims().values()));
                params.setRequestObjectFlow(true);
            }
        }
    }

    private String handleException(IdentityOAuth2Exception e) throws OAuthSystemException {

        if (log.isDebugEnabled()) {
            log.debug("Error while retrieving the login page url.", e);
        }
        throw new OAuthSystemException("Error when encoding login page URL");
    }

    private void addDataToSessionCache(OAuthMessage oAuthMessage, OAuth2Parameters params, String sessionDataKey) {

        SessionDataCacheKey cacheKey = new SessionDataCacheKey(sessionDataKey);
        SessionDataCacheEntry sessionDataCacheEntryNew = new SessionDataCacheEntry();
        sessionDataCacheEntryNew.setoAuth2Parameters(params);
        sessionDataCacheEntryNew.setQueryString(oAuthMessage.getRequest().getQueryString());

        if (oAuthMessage.getRequest().getParameterMap() != null) {
            sessionDataCacheEntryNew.setParamMap(new ConcurrentHashMap<>(oAuthMessage.getRequest().getParameterMap()));
        }
        sessionDataCacheEntryNew.setValidityPeriod(TimeUnit.MINUTES.toNanos(IdentityUtil.getTempDataCleanUpTimeout()));
        SessionDataCache.getInstance().addToCache(cacheKey, sessionDataCacheEntryNew);
    }

    private String analyzePromptParameter(OAuthMessage oAuthMessage, OAuth2Parameters params, String prompt) {

        List promptsList = getSupportedPromtsValues();
        boolean containsNone = (OAuthConstants.Prompt.NONE).equals(prompt);

        if (StringUtils.isNotBlank(prompt)) {
            List requestedPrompts = getRequestedPromptList(prompt);
            if (!CollectionUtils.containsAny(requestedPrompts, promptsList)) {
                String message = "Invalid prompt variables passed with the authorization request";
                return handleInvalidPromptValues(oAuthMessage, params, prompt, message);
            }

            if (requestedPrompts.size() > 1) {
                if (requestedPrompts.contains(OAuthConstants.Prompt.NONE)) {

                    String message =
                            "Invalid prompt variable combination. The value 'none' cannot be used with others " +
                                    "prompts. Prompt: ";
                    return handleInvalidPromptValues(oAuthMessage, params, prompt, message);

                } else if (requestedPrompts.contains(OAuthConstants.Prompt.LOGIN) &&
                        (requestedPrompts.contains(OAuthConstants.Prompt.CONSENT))) {
                    oAuthMessage.setForceAuthenticate(true);
                    oAuthMessage.setPassiveAuthentication(false);
                }
            } else {
                if ((OAuthConstants.Prompt.LOGIN).equals(prompt)) { // prompt for authentication
                    oAuthMessage.setForceAuthenticate(true);
                    oAuthMessage.setPassiveAuthentication(false);
                } else if (containsNone) {
                    oAuthMessage.setForceAuthenticate(false);
                    oAuthMessage.setPassiveAuthentication(true);
                } else if ((OAuthConstants.Prompt.CONSENT).equals(prompt)) {
                    oAuthMessage.setForceAuthenticate(false);
                    oAuthMessage.setPassiveAuthentication(false);
                }
            }
        }
        return null;
    }

    private String handleInvalidPromptValues(OAuthMessage oAuthMessage, OAuth2Parameters params, String prompt, String
            message) {

        if (log.isDebugEnabled()) {
            log.debug(message + " " + prompt);
        }
        OAuthProblemException ex = OAuthProblemException.error(OAuth2ErrorCodes.INVALID_REQUEST, message);
        return EndpointUtil.getErrorRedirectURL(oAuthMessage.getRequest(), ex, params);
    }

    private List getRequestedPromptList(String prompt) {

        String[] prompts = prompt.trim().split("\\s");
        return Arrays.asList(prompts);
    }

    private List<String> getSupportedPromtsValues() {

        return Arrays.asList(OAuthConstants.Prompt.NONE, OAuthConstants.Prompt.LOGIN,
                OAuthConstants.Prompt.CONSENT, OAuthConstants.Prompt.SELECT_ACCOUNT);
    }

    private String validatePKCEParameters(OAuthMessage oAuthMessage, OAuth2ClientValidationResponseDTO
            validationResponse, String pkceChallengeCode, String pkceChallengeMethod) {

        OAuth2Parameters oAuth2Parameters = getOAuth2ParamsFromOAuthMessage(oAuthMessage);

        // Check if PKCE is mandatory for the application
        if (validationResponse.isPkceMandatory()) {
            if (pkceChallengeCode == null || !OAuth2Util.validatePKCECodeChallenge(pkceChallengeCode,
                    pkceChallengeMethod)) {
                return getErrorPageURL(oAuthMessage.getRequest(), OAuth2ErrorCodes.INVALID_REQUEST, OAuth2ErrorCodes
                        .OAuth2SubErrorCodes.INVALID_PKCE_CHALLENGE_CODE, "PKCE is mandatory for this application. " +
                        "PKCE Challenge is not provided or is not upto RFC 7636 " +
                        "specification.", null, oAuth2Parameters);
            }
        }
        //Check if the code challenge method value is neither "plain" or "s256", if so return error
        if (pkceChallengeCode != null && pkceChallengeMethod != null) {
            if (!OAuthConstants.OAUTH_PKCE_PLAIN_CHALLENGE.equals(pkceChallengeMethod) &&
                    !OAuthConstants.OAUTH_PKCE_S256_CHALLENGE.equals(pkceChallengeMethod)) {
                return getErrorPageURL(oAuthMessage.getRequest(), OAuth2ErrorCodes.INVALID_REQUEST, OAuth2ErrorCodes
                        .OAuth2SubErrorCodes.INVALID_PKCE_CHALLENGE_CODE, "Unsupported PKCE Challenge Method", null,
                        oAuth2Parameters);
            }
        }

        // Check if "plain" transformation algorithm is disabled for the application
        if (pkceChallengeCode != null && !validationResponse.isPkceSupportPlain()) {
            if (pkceChallengeMethod == null || OAuthConstants.OAUTH_PKCE_PLAIN_CHALLENGE.equals(pkceChallengeMethod)) {
                return getErrorPageURL(oAuthMessage.getRequest(), OAuth2ErrorCodes.INVALID_REQUEST, OAuth2ErrorCodes
                        .OAuth2SubErrorCodes.INVALID_PKCE_CHALLENGE_CODE, "This application does not support " +
                        "\"plain\" transformation algorithm.", null, oAuth2Parameters);
            }
        }

        // If PKCE challenge code was sent, check if the code challenge is upto specifications
        if (pkceChallengeCode != null && !OAuth2Util.validatePKCECodeChallenge(pkceChallengeCode,
                pkceChallengeMethod)) {
            return getErrorPageURL(oAuthMessage.getRequest(), OAuth2ErrorCodes.INVALID_REQUEST, OAuth2ErrorCodes
                    .OAuth2SubErrorCodes.INVALID_PKCE_CHALLENGE_CODE, "Code challenge used is not up to RFC 7636 " +
                    "specifications.", null, oAuth2Parameters);
        }
        return null;
    }

    private boolean isPkceSupportEnabled() {

        return getOAuth2Service().isPKCESupportEnabled();
    }

    private String getSpDisplayName(String clientId) throws OAuthSystemException {

        if (getOAuthServerConfiguration().isShowDisplayNameInConsentPage()) {
            ServiceProvider serviceProvider = getServiceProvider(clientId);
            ServiceProviderProperty[] serviceProviderProperties = serviceProvider.getSpProperties();
            for (ServiceProviderProperty serviceProviderProperty : serviceProviderProperties) {
                if (DISPLAY_NAME.equals(serviceProviderProperty.getName())) {
                    return serviceProviderProperty.getValue();
                }
            }
        }
        return StringUtils.EMPTY;
    }

    private String populateOauthParameters(OAuth2Parameters params, OAuthMessage oAuthMessage,
                                           OAuth2ClientValidationResponseDTO validationResponse,
                                           OAuthAuthzRequest oauthRequest)
            throws OAuthSystemException, InvalidRequestException {

        String clientId = oAuthMessage.getClientId();
        params.setClientId(clientId);
        params.setRedirectURI(validationResponse.getCallbackURL());
        params.setResponseType(oauthRequest.getResponseType());
        params.setResponseMode(oauthRequest.getParam(RESPONSE_MODE));
        params.setScopes(oauthRequest.getScopes());
        if (params.getScopes() == null) { // to avoid null pointers
            Set<String> scopeSet = new HashSet<String>();
            scopeSet.add("");
            params.setScopes(scopeSet);
        }
        params.setState(oauthRequest.getState());
        params.setApplicationName(validationResponse.getApplicationName());

        String spDisplayName = getSpDisplayName(clientId);
        if (StringUtils.isNotBlank(spDisplayName)) {
            params.setDisplayName(spDisplayName);
        }

        // OpenID Connect specific request parameters
        params.setNonce(oauthRequest.getParam(OAuthConstants.OAuth20Params.NONCE));
        params.setDisplay(oauthRequest.getParam(OAuthConstants.OAuth20Params.DISPLAY));
        params.setIDTokenHint(oauthRequest.getParam(OAuthConstants.OAuth20Params.ID_TOKEN_HINT));
        params.setLoginHint(oauthRequest.getParam(OAuthConstants.OAuth20Params.LOGIN_HINT));

        // Set the service provider tenant domain.
        params.setTenantDomain(getSpTenantDomain(clientId));

        // Set the login tenant domain.
        String loginTenantDomain = getLoginTenantDomain(oAuthMessage, clientId);
        params.setLoginTenantDomain(loginTenantDomain);

        if (StringUtils.isNotBlank(oauthRequest.getParam(ACR_VALUES)) && !"null".equals(oauthRequest.getParam
                (ACR_VALUES))) {
            List acrValuesList = Arrays.asList(oauthRequest.getParam(ACR_VALUES).split(" "));
            LinkedHashSet acrValuesHashSet = new LinkedHashSet<>(acrValuesList);
            params.setACRValues(acrValuesHashSet);
            oAuthMessage.getRequest().setAttribute(ACR_VALUES, acrValuesList);
        }
        if (StringUtils.isNotBlank(oauthRequest.getParam(CLAIMS))) {
            params.setEssentialClaims(oauthRequest.getParam(CLAIMS));
        }

        handleMaxAgeParameter(oauthRequest, params);

        /*
            OIDC Request object will supersede parameters sent in the OAuth Authorization request. So handling the
            OIDC Request object needs to done after processing all request parameters.
         */
        if (OAuth2Util.isOIDCAuthzRequest(oauthRequest.getScopes())) {
            try {
                handleOIDCRequestObject(oAuthMessage, oauthRequest, params);
            } catch (RequestObjectException e) {
                if (log.isDebugEnabled()) {
                    log.debug("Request Object Handling failed due to : " + e.getErrorCode() + " for client_id: "
                            + clientId + " of tenantDomain: " + params.getTenantDomain(), e);
                }
                return EndpointUtil.getErrorPageURL(oAuthMessage.getRequest(), OAuth2ErrorCodes
                                .OAuth2SubErrorCodes.INVALID_REQUEST_OBJECT, e.getErrorCode(), e.getErrorMessage(),
                        null, params);
            }
        }

        if (isPkceSupportEnabled()) {
            String pkceChallengeCode = oAuthMessage.getOauthPKCECodeChallenge();
            String pkceChallengeMethod = oAuthMessage.getOauthPKCECodeChallengeMethod();
            String redirectURI = validatePKCEParameters(oAuthMessage, validationResponse, pkceChallengeCode,
                    pkceChallengeMethod);
            if (redirectURI != null) {
                return redirectURI;
            }
            params.setPkceCodeChallenge(pkceChallengeCode);
            params.setPkceCodeChallengeMethod(pkceChallengeMethod);
        }

        return null;
    }

    private String getSpTenantDomain(String clientId) throws InvalidRequestException {

        try {
            // At this point we have verified that a valid app exists for the client_id. So we directly get the SP
            // tenantDomain.
            return OAuth2Util.getTenantDomainOfOauthApp(clientId);
        } catch (InvalidOAuthClientException | IdentityOAuth2Exception e) {
            throw new InvalidRequestException("Error retrieving Service Provider tenantDomain for client_id: "
                    + clientId, OAuth2ErrorCodes.INVALID_REQUEST, OAuth2ErrorCodes.OAuth2SubErrorCodes
                    .UNEXPECTED_SERVER_ERROR);
        }
    }

    private String getLoginTenantDomain(OAuthMessage oAuthMessage, String clientId) throws InvalidRequestException {

        if (!IdentityTenantUtil.isTenantedSessionsEnabled()) {
            return getSpTenantDomain(clientId);
        }

        String loginTenantDomain =
                oAuthMessage.getRequest().getParameter(FrameworkConstants.RequestParams.LOGIN_TENANT_DOMAIN);
        if (StringUtils.isBlank(loginTenantDomain)) {
            return getSpTenantDomain(clientId);
        }
        return loginTenantDomain;
    }

    private void handleMaxAgeParameter(OAuthAuthzRequest oauthRequest,
                                       OAuth2Parameters params) throws InvalidRequestException {
        // Set max_age parameter sent in the authorization request.
        String maxAgeParam = oauthRequest.getParam(OAuthConstants.OIDCClaims.MAX_AGE);
        if (StringUtils.isNotBlank(maxAgeParam)) {
            try {
                params.setMaxAge(Long.parseLong(maxAgeParam));
            } catch (NumberFormatException ex) {
                log.error("Invalid max_age parameter: '" + maxAgeParam + "' sent in the authorization request.");
                throw new InvalidRequestException("Invalid max_age parameter value sent in the authorization request" +
                        ".", OAuth2ErrorCodes.INVALID_REQUEST, OAuth2ErrorCodes.OAuth2SubErrorCodes.INVALID_PARAMETERS);
            }
        }
    }

    private void handleOIDCRequestObject(OAuthMessage oAuthMessage, OAuthAuthzRequest oauthRequest,
                                         OAuth2Parameters parameters)
            throws RequestObjectException, InvalidRequestException {

        validateRequestObjectParams(oauthRequest);
        String requestObjValue = null;
        if (isRequestUri(oauthRequest)) {
            requestObjValue = oauthRequest.getParam(REQUEST_URI);
        } else if (isRequestParameter(oauthRequest)) {
            requestObjValue = oauthRequest.getParam(REQUEST);
        }

        if (StringUtils.isNotEmpty(requestObjValue)) {
            handleRequestObject(oAuthMessage, oauthRequest, parameters);
        } else {
            if (log.isDebugEnabled()) {
                log.debug("Authorization Request does not contain a Request Object or Request Object reference.");
            }
        }
    }

    private void validateRequestObjectParams(OAuthAuthzRequest oauthRequest) throws RequestObjectException {

        // With in the same request it can not be used both request parameter and request_uri parameter.
        if (StringUtils.isNotEmpty(oauthRequest.getParam(REQUEST)) && StringUtils.isNotEmpty(oauthRequest.getParam
                (REQUEST_URI))) {
            throw new RequestObjectException(OAuth2ErrorCodes.INVALID_REQUEST, "Both request and " +
                    "request_uri parameters can not be associated with the same authorization request.");
        }
    }

    private void handleRequestObject(OAuthMessage oAuthMessage, OAuthAuthzRequest oauthRequest,
                                     OAuth2Parameters parameters)
            throws RequestObjectException, InvalidRequestException {

        RequestObject requestObject = OIDCRequestObjectUtil.buildRequestObject(oauthRequest, parameters);
        if (requestObject == null) {
            throw new RequestObjectException(OAuth2ErrorCodes.INVALID_REQUEST, "Unable to build a valid Request " +
                    "Object from the authorization request.");
        }
            /*
              When the request parameter is used, the OpenID Connect request parameter values contained in the JWT
              supersede those passed using the OAuth 2.0 request syntax
             */
        overrideAuthzParameters(oAuthMessage, parameters, oauthRequest.getParam(REQUEST),
                oauthRequest.getParam(REQUEST_URI), requestObject);

        // If the redirect uri was not given in auth request the registered redirect uri will be available here,
        // so validating if the registered redirect uri is a single uri that can be properly redirected.
        if (StringUtils.isBlank(parameters.getRedirectURI()) ||
                StringUtils.startsWith(parameters.getRedirectURI(), REGEX_PATTERN)) {
            throw new InvalidRequestException("Redirect URI is not present in the authorization request.",
                    OAuth2ErrorCodes.INVALID_REQUEST, OAuth2ErrorCodes.OAuth2SubErrorCodes.INVALID_REDIRECT_URI);
        }
        persistRequestObject(parameters, requestObject);
    }

    private void overrideAuthzParameters(OAuthMessage oAuthMessage, OAuth2Parameters params,
                                         String requestParameterValue,
                                         String requestURIParameterValue, RequestObject requestObject) {

        if (StringUtils.isNotBlank(requestParameterValue) || StringUtils.isNotBlank(requestURIParameterValue)) {
            replaceIfPresent(requestObject, REDIRECT_URI, params::setRedirectURI);
            replaceIfPresent(requestObject, NONCE, params::setNonce);
            replaceIfPresent(requestObject, STATE, params::setState);
            replaceIfPresent(requestObject, DISPLAY, params::setDisplay);
            replaceIfPresent(requestObject, RESPONSE_MODE, params::setResponseMode);
            replaceIfPresent(requestObject, LOGIN_HINT, params::setLoginHint);
            replaceIfPresent(requestObject, ID_TOKEN_HINT, params::setIDTokenHint);
            replaceIfPresent(requestObject, PROMPT, params::setPrompt);
            replaceIfPresent(requestObject, CLAIMS, params::setEssentialClaims);

            if (StringUtils.isNotEmpty(requestObject.getClaimValue(SCOPE))) {
                String scopeString = requestObject.getClaimValue(SCOPE);
                params.setScopes(new HashSet<>(Arrays.asList(scopeString.split(SPACE_SEPARATOR))));
            }
            if (StringUtils.isNotEmpty(requestObject.getClaimValue(MAX_AGE))) {
                params.setMaxAge(Integer.parseInt(requestObject.getClaimValue(MAX_AGE)));
            }
            if (StringUtils.isNotEmpty(requestObject.getClaimValue(AUTH_TIME))) {
                params.setAuthTime(Long.parseLong(requestObject.getClaimValue(AUTH_TIME)));
            }
            if (StringUtils.isNotEmpty(requestObject.getClaimValue(ACR_VALUES))) {
                String acrString = requestObject.getClaimValue(ACR_VALUES);
                params.setACRValues(new LinkedHashSet<>(Arrays.asList(acrString.split(COMMA_SEPARATOR))));
                oAuthMessage.getRequest().setAttribute(ACR_VALUES,
                        new ArrayList<>(Arrays.asList(acrString.split(COMMA_SEPARATOR))));
            } else {
                List<String> acrRequestedValues = getAcrValues(requestObject);
                if (CollectionUtils.isNotEmpty(acrRequestedValues)) {
                    oAuthMessage.getRequest().setAttribute(ACR_VALUES, acrRequestedValues);
                }
            }
        }
    }

    /**
     * To get the value(s) for "acr" from request object.
     *
     * @param requestObject {@link RequestObject}
     * @return list of acr value(s)
     */
    private List<String> getAcrValues(RequestObject requestObject) {

        List<String> acrRequestedValues = null;
        if (requestObject != null) {
            Map<String, List<RequestedClaim>> requestedClaims = requestObject.getRequestedClaims();
            List<RequestedClaim> requestedClaimsForIdToken = requestedClaims.get(OIDCConstants.ID_TOKEN);
            if (CollectionUtils.isNotEmpty(requestedClaimsForIdToken)) {
                for (RequestedClaim requestedClaim : requestedClaimsForIdToken) {
                    if (OAuthConstants.ACR.equalsIgnoreCase(requestedClaim.getName()) && requestedClaim.isEssential()) {
                        acrRequestedValues = requestedClaim.getValues();
                        if (CollectionUtils.isEmpty(acrRequestedValues) && StringUtils
                                .isNotEmpty(requestedClaim.getValue())) {
                            acrRequestedValues = Collections.singletonList(requestedClaim.getValue());
                        }
                        break;
                    }
                }
            }
        }
        return acrRequestedValues;
    }

    private void replaceIfPresent(RequestObject requestObject, String claim, Consumer<String> consumer) {

        String claimValue = requestObject.getClaimValue(claim);
        if (StringUtils.isNotEmpty(claimValue)) {
            consumer.accept(claimValue);
        }
    }

    private static boolean isRequestUri(OAuthAuthzRequest oAuthAuthzRequest) {

        String param = oAuthAuthzRequest.getParam(REQUEST_URI);
        return StringUtils.isNotBlank(param);
    }

    private static boolean isRequestParameter(OAuthAuthzRequest oAuthAuthzRequest) {

        String param = oAuthAuthzRequest.getParam(REQUEST);
        return StringUtils.isNotBlank(param);
    }

    private OAuth2ClientValidationResponseDTO validateClient(OAuthMessage oAuthMessage) {

        String redirectUri = oAuthMessage.getRequest().getParameter(REDIRECT_URI);
        return getOAuth2Service().validateClientInfo(oAuthMessage.getClientId(), redirectUri);
    }

    /**
     * Return ServiceProvider for the given clientId
     *
     * @param clientId clientId
     * @return ServiceProvider ServiceProvider
     * @throws OAuthSystemException if couldn't retrieve ServiceProvider Information
     */
    private ServiceProvider getServiceProvider(String clientId) throws OAuthSystemException {

        try {
            return OAuth2Util.getServiceProvider(clientId);
        } catch (IdentityOAuth2Exception e) {
            String msg = "Couldn't retrieve Service Provider for clientId: " + clientId;
            log.error(msg, e);
            throw new OAuthSystemException(msg, e);
        }
    }

    /**
     * prompt : none
     * The Authorization Server MUST NOT display any authentication
     * or consent user interface pages. An error is returned if the
     * End-User is not already authenticated or the Client does not
     * have pre-configured consent for the requested scopes. This
     * can be used as a method to check for existing authentication
     * and/or consent.
     * <p/>
     * prompt : consent
     * The Authorization Server MUST prompt the End-User for consent before
     * returning information to the Client.
     * <p/>
     * prompt Error : consent_required
     * The Authorization Server requires End-User consent. This
     * error MAY be returned when the prompt parameter in the
     * Authorization Request is set to none to request that the
     * Authorization Server should not display any user
     * interfaces to the End-User, but the Authorization Request
     * cannot be completed without displaying a user interface
     * for End-User consent.
     *
     * @return String URL
     * @throws OAuthSystemException OAuthSystemException
     */
    private String doUserAuthorization(OAuthMessage oAuthMessage, String sessionDataKeyFromLogin,
                                       OIDCSessionState sessionState)
            throws OAuthSystemException, ConsentHandlingFailedException, OAuthProblemException {

        OAuth2Parameters oauth2Params = getOauth2Params(oAuthMessage);
        AuthenticatedUser authenticatedUser = getLoggedInUser(oAuthMessage);
        boolean hasUserApproved = isUserAlreadyApproved(oauth2Params, authenticatedUser);

        if (hasPromptContainsConsent(oauth2Params)) {
            // Remove any existing consents.
            String clientId = oauth2Params.getClientId();
            OpenIDConnectUserRPStore.getInstance().removeConsentForUser(authenticatedUser, clientId);
            if (log.isDebugEnabled()) {
                log.debug("Prompt parameter contains 'consent'. Existing consents for user: "
                        + authenticatedUser.toFullQualifiedUsername() + " for oauth app with clientId: " + clientId
                        + " are revoked and user will be prompted to give consent again.");
            }
            // Need to prompt for consent and get user consent for claims as well.
            return promptUserForConsent(sessionDataKeyFromLogin, oauth2Params, authenticatedUser, true, oAuthMessage);
        } else if (isPromptNone(oauth2Params)) {
            return handlePromptNone(oAuthMessage, sessionState, oauth2Params, authenticatedUser, hasUserApproved);
        } else if (isPromptLogin(oauth2Params) || isPromptParamsNotPresent(oauth2Params)) {
            return handleConsent(oAuthMessage, sessionDataKeyFromLogin, sessionState, oauth2Params, authenticatedUser,
                    hasUserApproved);
        } else {
            return StringUtils.EMPTY;
        }
    }

    private OAuth2Parameters getOauth2Params(OAuthMessage oAuthMessage) {

        return oAuthMessage.getSessionDataCacheEntry().getoAuth2Parameters();
    }

    private AuthenticatedUser getLoggedInUser(OAuthMessage oAuthMessage) {

        return oAuthMessage.getSessionDataCacheEntry().getLoggedInUser();
    }

    private String handleConsent(OAuthMessage oAuthMessage, String sessionDataKey,
                                 OIDCSessionState sessionState, OAuth2Parameters oauth2Params,
                                 AuthenticatedUser authenticatedUser, boolean hasUserApproved)
            throws OAuthSystemException, ConsentHandlingFailedException {

        ServiceProvider serviceProvider = getServiceProvider(oauth2Params.getClientId());

        if (isConsentSkipped(serviceProvider)) {
            sessionState.setAddSessionState(true);
            return handleUserConsent(oAuthMessage, APPROVE, sessionState);
        } else if (hasUserApproved) {
            return handleApproveAlwaysWithPromptForNewConsent(oAuthMessage, sessionState, oauth2Params);
        } else {
            return promptUserForConsent(sessionDataKey, oauth2Params, authenticatedUser, false, oAuthMessage);
        }
    }

    private boolean isPromptParamsNotPresent(OAuth2Parameters oauth2Params) {

        return StringUtils.isBlank(oauth2Params.getPrompt());
    }

    private boolean isPromptLogin(OAuth2Parameters oauth2Params) {

        return (OAuthConstants.Prompt.LOGIN).equals(oauth2Params.getPrompt());
    }

    private String promptUserForConsent(String sessionDataKey, OAuth2Parameters oauth2Params,
                                        AuthenticatedUser user, boolean ignoreExistingConsents,
                                        OAuthMessage oAuthMessage)
            throws ConsentHandlingFailedException, OAuthSystemException {

        String clientId = oauth2Params.getClientId();
        String tenantDomain = oauth2Params.getTenantDomain();

        String preConsent;
        if (ignoreExistingConsents) {
            // Ignore existing consents and prompt for all SP mandatory and requested claims.
            if (log.isDebugEnabled()) {
                log.debug("Initiating consent handling for user: " + user.toFullQualifiedUsername() + " for client_id: "
                        + clientId + "  of tenantDomain: " + tenantDomain + " excluding existing consents.");
            }
            preConsent = handlePreConsentExcludingExistingConsents(oauth2Params, user);
        } else {
            if (log.isDebugEnabled()) {
                log.debug("Initiating consent handling for user: " + user.toFullQualifiedUsername() + " for client_id: "
                        + clientId + "  of tenantDomain: " + tenantDomain + " including existing consents.");
            }
            preConsent = handlePreConsentIncludingExistingConsents(oauth2Params, user);
        }

        return getUserConsentURL(sessionDataKey, oauth2Params, user, preConsent, oAuthMessage);
    }

    private String handlePromptNone(OAuthMessage oAuthMessage,
                                    OIDCSessionState sessionState,
                                    OAuth2Parameters oauth2Params,
                                    AuthenticatedUser authenticatedUser,
                                    boolean hasUserApproved) throws OAuthSystemException,
            ConsentHandlingFailedException, OAuthProblemException {

        if (isUserSessionNotExists(authenticatedUser)) {
            // prompt=none but user is not logged in. Therefore throw error according to
            // http://openid.net/specs/openid-connect-core-1_0.html#AuthRequest
            sessionState.setAddSessionState(true);
            throw OAuthProblemException.error(OAuth2ErrorCodes.LOGIN_REQUIRED,
                    "Request with \'prompt=none\' but user session does not exist");
        }

        if (isIdTokenHintExists(oauth2Params)) {
            // prompt=none with id_token_hint parameter with an id_token indicating a previously authenticated session.
            return handleIdTokenHint(oAuthMessage, sessionState, oauth2Params, authenticatedUser, hasUserApproved);
        } else {
            // Handle previously approved consent for prompt=none scenario
            return handlePreviouslyApprovedConsent(oAuthMessage, sessionState, oauth2Params, hasUserApproved);
        }
    }

    /**
     * Consent page can be skipped by setting OpenIDConnect configuration or by setting SP property.
     *
     * @param serviceProvider Service provider related to this request.
     * @return A boolean stating whether consent page is skipped or not.
     */
    private boolean isConsentSkipped(ServiceProvider serviceProvider) {

        return getOAuthServerConfiguration().getOpenIDConnectSkipeUserConsentConfig()
                || FrameworkUtils.isConsentPageSkippedForSP(serviceProvider);
    }

    private boolean isConsentFromUserRequired(String preConsentQueryParams) {

        return StringUtils.isNotBlank(preConsentQueryParams);
    }

    private String handlePreConsentExcludingExistingConsents(OAuth2Parameters oauth2Params, AuthenticatedUser user)
            throws ConsentHandlingFailedException, OAuthSystemException {

        return handlePreConsent(oauth2Params, user, false);
    }

    private String handlePreConsentIncludingExistingConsents(OAuth2Parameters oauth2Params, AuthenticatedUser user)
            throws ConsentHandlingFailedException, OAuthSystemException {

        return handlePreConsent(oauth2Params, user, true);
    }

    /**
     * Handle user consent from claims that will be shared in OIDC responses. Claims that require consent will be
     * sent to the consent page as query params. Consent page will interpret the query params and prompt the user
     * for consent.
     *
     * @param oauth2Params
     * @param user                Authenticated User
     * @param useExistingConsents Whether to consider existing user consents
     * @return
     * @throws ConsentHandlingFailedException
     * @throws OAuthSystemException
     */
    private String handlePreConsent(OAuth2Parameters oauth2Params, AuthenticatedUser user, boolean useExistingConsents)
            throws ConsentHandlingFailedException, OAuthSystemException {

        String additionalQueryParam = StringUtils.EMPTY;
        String clientId = oauth2Params.getClientId();
        String spTenantDomain = oauth2Params.getTenantDomain();
        ServiceProvider serviceProvider = getServiceProvider(clientId);

        if (log.isDebugEnabled()) {
            log.debug("Initiating consent handling for user: " + user.toFullQualifiedUsername() + " for client_id: "
                    + clientId + " of tenantDomain: " + spTenantDomain);
        }

        if (isConsentHandlingFromFrameworkSkipped(oauth2Params)) {
            if (log.isDebugEnabled()) {
                log.debug("Consent handling from framework skipped for client_id: " + clientId + " of tenantDomain: "
                        + spTenantDomain + " for user: " + user.toFullQualifiedUsername());
            }
            return StringUtils.EMPTY;
        }

        try {
            ConsentClaimsData claimsForApproval = getConsentRequiredClaims(user, serviceProvider, useExistingConsents);
            if (claimsForApproval != null) {
                String requestClaimsQueryParam = null;
                // Get the mandatory claims and append as query param.
                String mandatoryClaimsQueryParam = null;
                // Remove the claims which dont have values given by the user.
                claimsForApproval.setRequestedClaims(
                        removeConsentRequestedNullUserAttributes(claimsForApproval.getRequestedClaims(),
                                user.getUserAttributes(), spTenantDomain));
                List<ClaimMetaData> requestedOidcClaimsList =
                        getRequestedOidcClaimsList(claimsForApproval, oauth2Params, spTenantDomain);
                if (CollectionUtils.isNotEmpty(requestedOidcClaimsList)) {
                    requestClaimsQueryParam = REQUESTED_CLAIMS + "=" +
                            buildConsentClaimString(requestedOidcClaimsList);
                }

                if (CollectionUtils.isNotEmpty(claimsForApproval.getMandatoryClaims())) {
                    mandatoryClaimsQueryParam = MANDATORY_CLAIMS + "=" +
                            buildConsentClaimString(claimsForApproval.getMandatoryClaims());
                }

                additionalQueryParam = buildQueryParamString(requestClaimsQueryParam, mandatoryClaimsQueryParam);
            }
        } catch (UnsupportedEncodingException | SSOConsentServiceException e) {
            String msg = "Error while handling user consent for claim for user: " + user.toFullQualifiedUsername() +
                    " for client_id: " + clientId + " of tenantDomain: " + spTenantDomain;
            throw new ConsentHandlingFailedException(msg, e);
        } catch (ClaimMetadataException e) {
            throw new ConsentHandlingFailedException("Error while getting claim mappings for " + OIDC_DIALECT, e);
        } catch (RequestObjectException e) {
            throw new ConsentHandlingFailedException("Error while getting essential claims for the session data key " +
                    ": " + oauth2Params.getSessionDataKey(), e);
        }

        if (log.isDebugEnabled()) {
            log.debug("Additional Query param to be sent to consent page for user: " + user.toFullQualifiedUsername() +
                    " for client_id: " + clientId + " is '" + additionalQueryParam + "'");
        }

        return additionalQueryParam;
    }

    /**
     * Filter out the requested claims with the user attributes.
     *
     * @param requestedClaims List of requested claims metadata.
     * @param userAttributes  Authenticated users' attributes.
     * @param spTenantDomain  Tenant domain.
     * @return Filtered claims with user attributes.
     * @throws ClaimMetadataException If an error occurred while getting claim mappings.
     */
    private List<ClaimMetaData> removeConsentRequestedNullUserAttributes(List<ClaimMetaData> requestedClaims,
                                                                         Map<ClaimMapping, String> userAttributes,
                                                                         String spTenantDomain)
            throws ClaimMetadataException {

        List<String> localClaims = new ArrayList<>();
        List<ClaimMetaData> filteredRequestedClaims = new ArrayList<>();
        List<String> localClaimUris = new ArrayList<>();

        if (requestedClaims != null && userAttributes != null) {
            for (Map.Entry<ClaimMapping, String> attribute : userAttributes.entrySet()) {
                localClaims.add(attribute.getKey().getLocalClaim().getClaimUri());
            }
            if (CollectionUtils.isNotEmpty(localClaims)) {
                Set<ExternalClaim> externalClaimSetOfOidcClaims = ClaimMetadataHandler.getInstance()
                        .getMappingsFromOtherDialectToCarbon(OIDC_DIALECT, new HashSet<String>(localClaims),
                                spTenantDomain);
                for (ExternalClaim externalClaim : externalClaimSetOfOidcClaims) {
                    localClaimUris.add(externalClaim.getMappedLocalClaim());
                }
            }
            for (ClaimMetaData claimMetaData : requestedClaims) {
                if (localClaimUris.contains(claimMetaData.getClaimUri())) {
                    filteredRequestedClaims.add(claimMetaData);
                }
            }
        }
        return filteredRequestedClaims;
    }

    /**
     * Filter requested claims based on OIDC claims and return the claims which includes in OIDC.
     *
     * @param claimsForApproval         Consent required claims.
     * @param oauth2Params              OAuth parameters.
     * @param spTenantDomain            Tenant domain.
     * @return                          Requested OIDC claim list.
     * @throws RequestObjectException   If an error occurred while getting essential claims for the session data key.
     * @throws ClaimMetadataException   If an error occurred while getting claim mappings.
     */
    private List<ClaimMetaData> getRequestedOidcClaimsList(ConsentClaimsData claimsForApproval,
                                                           OAuth2Parameters oauth2Params, String spTenantDomain)
            throws RequestObjectException, ClaimMetadataException {

        List<ClaimMetaData> requestedOidcClaimsList = new ArrayList<>();
        List<String> localClaimsOfOidcClaims = new ArrayList<>();
        List<String> localClaimsOfEssentialClaims = new ArrayList<>();

        // Get the claims uri list of all the requested scopes. Eg:- country, email.
        List<String> claimListOfScopes =
                openIDConnectClaimFilter.getClaimsFilteredByOIDCScopes(oauth2Params.getScopes(), spTenantDomain);

        List<String> essentialRequestedClaims = new ArrayList<>();

        if (oauth2Params.isRequestObjectFlow()) {
            // Get the requested claims came through request object.
            List<RequestedClaim> requestedClaimsOfIdToken = EndpointUtil.getRequestObjectService()
                    .getRequestedClaimsForSessionDataKey(oauth2Params.getSessionDataKey(), false);

            List<RequestedClaim> requestedClaimsOfUserInfo = EndpointUtil.getRequestObjectService()
                    .getRequestedClaimsForSessionDataKey(oauth2Params.getSessionDataKey(), true);


            // Get the list of id token's essential claims.
            for (RequestedClaim requestedClaim : requestedClaimsOfIdToken) {
                if (requestedClaim.isEssential()) {
                    essentialRequestedClaims.add(requestedClaim.getName());
                }
            }

            // Get the list of user info's essential claims.
            for (RequestedClaim requestedClaim : requestedClaimsOfUserInfo) {
                if (requestedClaim.isEssential()) {
                    essentialRequestedClaims.add(requestedClaim.getName());
                }
            }
        }

        if (CollectionUtils.isNotEmpty(claimListOfScopes)) {
            // Get the external claims relevant to all oidc scope claims and essential claims.
            Set<ExternalClaim> externalClaimSetOfOidcClaims = ClaimMetadataHandler.getInstance()
                    .getMappingsFromOtherDialectToCarbon(OIDC_DIALECT, new HashSet<String>(claimListOfScopes),
                            spTenantDomain);

            /* Get the locally mapped claims for all the external claims of requested scope and essential claims.
            Eg:- http://wso2.org/claims/country, http://wso2.org/claims/emailaddress
             */
            for (ExternalClaim externalClaim : externalClaimSetOfOidcClaims) {
                localClaimsOfOidcClaims.add(externalClaim.getMappedLocalClaim());
            }
        }

        if (CollectionUtils.isNotEmpty(essentialRequestedClaims)) {
            // Get the external claims relevant to all essential requested claims.
            Set<ExternalClaim> externalClaimSetOfEssentialClaims = ClaimMetadataHandler.getInstance()
                    .getMappingsFromOtherDialectToCarbon(OIDC_DIALECT, new HashSet<String>(essentialRequestedClaims),
                            spTenantDomain);

            /* Get the locally mapped claims for all the external claims of essential claims.
            Eg:- http://wso2.org/claims/country, http://wso2.org/claims/emailaddress
             */
            for (ExternalClaim externalClaim : externalClaimSetOfEssentialClaims) {
                localClaimsOfEssentialClaims.add(externalClaim.getMappedLocalClaim());
            }
        }

        /* Check whether the local claim of oidc claims contains the requested claims or essential claims of
         request object contains the requested claims, If it contains add it as requested claim.
         */
        for (ClaimMetaData claimMetaData : claimsForApproval.getRequestedClaims()) {
            if (localClaimsOfOidcClaims.contains(claimMetaData.getClaimUri()) ||
                    localClaimsOfEssentialClaims.contains(claimMetaData.getClaimUri())) {
                requestedOidcClaimsList.add(claimMetaData);
            }
        }

        return requestedOidcClaimsList;
    }

    private ConsentClaimsData getConsentRequiredClaims(AuthenticatedUser user,
                                                       ServiceProvider serviceProvider,
                                                       boolean useExistingConsents) throws SSOConsentServiceException {

        if (useExistingConsents) {
            return getSSOConsentService().getConsentRequiredClaimsWithExistingConsents(serviceProvider, user);
        } else {
            return getSSOConsentService().getConsentRequiredClaimsWithoutExistingConsents(serviceProvider, user);
        }
    }

    private String buildQueryParamString(String firstQueryParam, String secondQueryParam) {

        StringJoiner joiner = new StringJoiner("&");
        if (StringUtils.isNotBlank(firstQueryParam)) {
            joiner.add(firstQueryParam);
        }

        if (StringUtils.isNotBlank(secondQueryParam)) {
            joiner.add(secondQueryParam);
        }

        return joiner.toString();
    }

    private String buildConsentClaimString(List<ClaimMetaData> consentClaimsData) throws UnsupportedEncodingException {

        StringJoiner joiner = new StringJoiner(",");
        for (ClaimMetaData claimMetaData : consentClaimsData) {
            joiner.add(claimMetaData.getId() + "_" + claimMetaData.getDisplayName());
        }
        return URLEncoder.encode(joiner.toString(), StandardCharsets.UTF_8.toString());
    }

    private String handleIdTokenHint(OAuthMessage oAuthMessage,
                                     OIDCSessionState sessionState,
                                     OAuth2Parameters oauth2Params,
                                     AuthenticatedUser loggedInUser,
                                     boolean hasUserApproved) throws OAuthSystemException,
            ConsentHandlingFailedException, OAuthProblemException {

        sessionState.setAddSessionState(true);
        try {
            String idTokenHint = oauth2Params.getIDTokenHint();
            if (isIdTokenValidationFailed(idTokenHint)) {
                throw OAuthProblemException.error(OAuth2ErrorCodes.ACCESS_DENIED,
                        "Request with \'id_token_hint=" + idTokenHint +
                                "\' but ID Token validation failed");
            }

            String loggedInUserSubjectId = loggedInUser.getAuthenticatedSubjectIdentifier();
            if (isIdTokenSubjectEqualsToLoggedInUser(loggedInUserSubjectId, idTokenHint)) {
                return handlePreviouslyApprovedConsent(oAuthMessage, sessionState, oauth2Params, hasUserApproved);
            } else {
                throw OAuthProblemException.error(OAuth2ErrorCodes.LOGIN_REQUIRED,
                        "Request with \'id_token_hint=" + idTokenHint +
                                "\' but user has denied the consent");
            }
        } catch (ParseException e) {
            String msg = "Error while getting clientId from the IdTokenHint.";
            log.error(msg, e);
            throw OAuthProblemException.error(OAuth2ErrorCodes.ACCESS_DENIED, msg);
        }
    }

    private String handlePreviouslyApprovedConsent(OAuthMessage oAuthMessage, OIDCSessionState sessionState,
                                                   OAuth2Parameters oauth2Params, boolean hasUserApproved)
            throws OAuthSystemException, ConsentHandlingFailedException, OAuthProblemException {

        ServiceProvider serviceProvider = getServiceProvider(oauth2Params.getClientId());
        sessionState.setAddSessionState(true);
        if (isConsentSkipped(serviceProvider)) {
            return handleUserConsent(oAuthMessage, APPROVE, sessionState);
        } else if (hasUserApproved) {
            return handleApprovedAlwaysWithoutPromptingForNewConsent(oAuthMessage, sessionState, oauth2Params);
        } else {
            throw OAuthProblemException.error(OAuth2ErrorCodes.CONSENT_REQUIRED,
                    "Required consent not found");
        }
    }

    private String handleApprovedAlwaysWithoutPromptingForNewConsent(OAuthMessage oAuthMessage,
                                                                     OIDCSessionState sessionState,
                                                                     OAuth2Parameters oauth2Params)
            throws ConsentHandlingFailedException, OAuthSystemException, OAuthProblemException {

        AuthenticatedUser authenticatedUser = getLoggedInUser(oAuthMessage);
        String preConsent = handlePreConsentIncludingExistingConsents(oauth2Params, authenticatedUser);

        if (isConsentFromUserRequired(preConsent)) {
            throw OAuthProblemException.error(OAuth2ErrorCodes.CONSENT_REQUIRED,
                    "Consent approved always without prompting for new consent");
        } else {
            return handleUserConsent(oAuthMessage, APPROVE, sessionState);
        }
    }

    private String handleApproveAlwaysWithPromptForNewConsent(OAuthMessage oAuthMessage, OIDCSessionState sessionState,
                                                              OAuth2Parameters oauth2Params)
            throws ConsentHandlingFailedException, OAuthSystemException {

        AuthenticatedUser authenticatedUser = getLoggedInUser(oAuthMessage);
        String preConsent = handlePreConsentIncludingExistingConsents(oauth2Params, authenticatedUser);

        if (isConsentFromUserRequired(preConsent)) {
            String sessionDataKeyFromLogin = getSessionDataKeyFromLogin(oAuthMessage);
            preConsent = buildQueryParamString(preConsent, USER_CLAIMS_CONSENT_ONLY + "=true");

            return getUserConsentURL(sessionDataKeyFromLogin, oauth2Params,
                    authenticatedUser, preConsent, oAuthMessage);
        } else {
            sessionState.setAddSessionState(true);
            return handleUserConsent(oAuthMessage, APPROVE, sessionState);
        }
    }

    private boolean isIdTokenHintExists(OAuth2Parameters oauth2Params) {

        return StringUtils.isNotEmpty(oauth2Params.getIDTokenHint());
    }

    private boolean isUserAlreadyApproved(OAuth2Parameters oauth2Params, AuthenticatedUser user)
            throws OAuthSystemException {

        try {
            return EndpointUtil.isUserAlreadyConsentedForOAuthScopes(user, oauth2Params);
        } catch (IdentityOAuth2ScopeException | IdentityOAuthAdminException e) {
            throw new OAuthSystemException("Error occurred while checking user has already approved the consent " +
                    "required OAuth scopes.", e);
        }
    }

    private boolean isIdTokenSubjectEqualsToLoggedInUser(String loggedInUser, String idTokenHint)
            throws ParseException {

        String subjectValue = getSubjectFromIdToken(idTokenHint);
        return StringUtils.isNotEmpty(loggedInUser) && loggedInUser.equals(subjectValue);
    }

    private String getSubjectFromIdToken(String idTokenHint) throws ParseException {

        return SignedJWT.parse(idTokenHint).getJWTClaimsSet().getSubject();
    }

    private boolean isIdTokenValidationFailed(String idTokenHint) {

        if (!OAuth2Util.validateIdToken(idTokenHint)) {
            log.error("ID token signature validation failed.");
            return true;
        }
        return false;
    }

    private boolean isUserSessionNotExists(AuthenticatedUser user) {

        return user == null;
    }

    private boolean isPromptNone(OAuth2Parameters oauth2Params) {

        return (OAuthConstants.Prompt.NONE).equals(oauth2Params.getPrompt());
    }

    private boolean hasPromptContainsConsent(OAuth2Parameters oauth2Params) {

        String[] prompts = null;
        if (StringUtils.isNotBlank(oauth2Params.getPrompt())) {
            prompts = oauth2Params.getPrompt().trim().split("\\s");
        }
        return prompts != null && Arrays.asList(prompts).contains(OAuthConstants.Prompt.CONSENT);
    }

    private String getUserConsentURL(String sessionDataKey,
                                     OAuth2Parameters oauth2Params,
                                     AuthenticatedUser user, OAuthMessage oAuthMessage) throws OAuthSystemException {

        String loggedInUser = user.getAuthenticatedSubjectIdentifier();
        return EndpointUtil.getUserConsentURL(oauth2Params, loggedInUser, sessionDataKey,
                OAuth2Util.isOIDCAuthzRequest(oauth2Params.getScopes()), oAuthMessage);
    }

    private String getUserConsentURL(String sessionDataKey,
                                     OAuth2Parameters oauth2Params,
                                     AuthenticatedUser authenticatedUser,
                                     String additionalQueryParams, OAuthMessage oAuthMessage)
            throws OAuthSystemException {

        String userConsentURL = getUserConsentURL(sessionDataKey, oauth2Params, authenticatedUser, oAuthMessage);
        return FrameworkUtils.appendQueryParamsStringToUrl(userConsentURL, additionalQueryParams);
    }

    /**
     * Here we set the authenticated user to the session data
     *
     * @param oauth2Params
     * @return
     */
    private OAuth2AuthorizeRespDTO authorize(OAuth2Parameters oauth2Params,
                                             SessionDataCacheEntry sessionDataCacheEntry,
                                             HttpRequestHeaderHandler httpRequestHeaderHandler) {

        OAuth2AuthorizeReqDTO authzReqDTO =
                buildAuthRequest(oauth2Params, sessionDataCacheEntry, httpRequestHeaderHandler);
        return getOAuth2Service().authorize(authzReqDTO);
    }

    private OAuth2AuthorizeReqDTO buildAuthRequest(OAuth2Parameters oauth2Params, SessionDataCacheEntry
            sessionDataCacheEntry, HttpRequestHeaderHandler httpRequestHeaderHandler) {

        OAuth2AuthorizeReqDTO authzReqDTO = new OAuth2AuthorizeReqDTO();
        authzReqDTO.setCallbackUrl(oauth2Params.getRedirectURI());
        authzReqDTO.setConsumerKey(oauth2Params.getClientId());
        authzReqDTO.setResponseType(oauth2Params.getResponseType());
        authzReqDTO.setScopes(oauth2Params.getScopes().toArray(new String[oauth2Params.getScopes().size()]));
        authzReqDTO.setUser(sessionDataCacheEntry.getLoggedInUser());
        authzReqDTO.setACRValues(oauth2Params.getACRValues());
        authzReqDTO.setNonce(oauth2Params.getNonce());
        authzReqDTO.setPkceCodeChallenge(oauth2Params.getPkceCodeChallenge());
        authzReqDTO.setPkceCodeChallengeMethod(oauth2Params.getPkceCodeChallengeMethod());
        authzReqDTO.setTenantDomain(oauth2Params.getTenantDomain());
        authzReqDTO.setAuthTime(sessionDataCacheEntry.getAuthTime());
        authzReqDTO.setMaxAge(oauth2Params.getMaxAge());
        authzReqDTO.setEssentialClaims(oauth2Params.getEssentialClaims());
        authzReqDTO.setSessionDataKey(oauth2Params.getSessionDataKey());
        authzReqDTO.setRequestObjectFlow(oauth2Params.isRequestObjectFlow());
        authzReqDTO.setIdpSessionIdentifier(sessionDataCacheEntry.getSessionContextIdentifier());
        authzReqDTO.setLoginTenantDomain(oauth2Params.getLoginTenantDomain());
        if (sessionDataCacheEntry.getParamMap() != null && sessionDataCacheEntry.getParamMap().get(OAuthConstants
                .AMR) != null) {
            authzReqDTO.addProperty(OAuthConstants.AMR, sessionDataCacheEntry.getParamMap().get(OAuthConstants.AMR));
        }
        // Set Selected acr value.
        String[] sessionIds = sessionDataCacheEntry.getParamMap().get(FrameworkConstants.SESSION_DATA_KEY);
        if (ArrayUtils.isNotEmpty(sessionIds)) {
            String commonAuthSessionId = sessionIds[0];
            SessionContext sessionContext = FrameworkUtils.getSessionContextFromCache(commonAuthSessionId,
                    oauth2Params.getLoginTenantDomain());
            if (sessionContext != null && sessionContext.getSessionAuthHistory() != null) {
                authzReqDTO.setSelectedAcr(sessionContext.getSessionAuthHistory().getSelectedAcrValue());
            }
        }
        // Adding Httprequest headers and cookies in AuthzDTO.
        authzReqDTO.setHttpRequestHeaders(httpRequestHeaderHandler.getHttpRequestHeaders());
        authzReqDTO.setCookie(httpRequestHeaderHandler.getCookies());
        return authzReqDTO;
    }

    private AuthenticationResult getAuthenticationResult(OAuthMessage oAuthMessage, String sessionDataKey) {

        AuthenticationResult result = getAuthenticationResultFromRequest(oAuthMessage.getRequest());
        if (result == null) {
            isCacheAvailable = true;
            result = getAuthenticationResultFromCache(sessionDataKey);
        }
        return result;
    }

    private AuthenticationResult getAuthenticationResultFromCache(String sessionDataKey) {

        AuthenticationResult authResult = null;
        AuthenticationResultCacheEntry authResultCacheEntry = FrameworkUtils
                .getAuthenticationResultFromCache(sessionDataKey);
        if (authResultCacheEntry != null) {
            authResult = authResultCacheEntry.getResult();
        } else {
            log.error("Cannot find AuthenticationResult from the cache");
        }
        return authResult;
    }

    /**
     * Get authentication result from request
     *
     * @param request Http servlet request
     * @return AuthenticationResult
     */
    private AuthenticationResult getAuthenticationResultFromRequest(HttpServletRequest request) {

        return (AuthenticationResult) request.getAttribute(FrameworkConstants.RequestAttribute.AUTH_RESULT);
    }

    private Response handleAuthFlowThroughFramework(OAuthMessage oAuthMessage)
            throws URISyntaxException, InvalidRequestParentException {

        try {
            CommonAuthResponseWrapper responseWrapper = new CommonAuthResponseWrapper(oAuthMessage.getResponse());
            invokeCommonauthFlow(oAuthMessage, responseWrapper);
            return processAuthResponseFromFramework(oAuthMessage, responseWrapper);
        } catch (ServletException | IOException | URLBuilderException e) {
            log.error("Error occurred while sending request to authentication framework.");
            return Response.status(HttpServletResponse.SC_INTERNAL_SERVER_ERROR).build();
        }
    }

    private Response processAuthResponseFromFramework(OAuthMessage oAuthMessage,
                                                      CommonAuthResponseWrapper responseWrapper)
            throws IOException, InvalidRequestParentException, URISyntaxException, URLBuilderException {

        if (isAuthFlowStateExists(oAuthMessage)) {
            if (isFlowStateIncomplete(oAuthMessage)) {
                return handleIncompleteFlow(oAuthMessage, responseWrapper);
            } else {
                return handleSuccessfullyCompletedFlow(oAuthMessage);
            }
        } else {
            return handleUnknownFlowState(oAuthMessage);
        }
    }

    private Response handleUnknownFlowState(OAuthMessage oAuthMessage)
            throws URISyntaxException, InvalidRequestParentException {

        oAuthMessage.getRequest().setAttribute(FrameworkConstants.RequestParams.FLOW_STATUS, AuthenticatorFlowStatus
                .UNKNOWN);
        return authorize(oAuthMessage.getRequest(), oAuthMessage.getResponse());
    }

    private Response handleSuccessfullyCompletedFlow(OAuthMessage oAuthMessage)
            throws URISyntaxException, InvalidRequestParentException {

        return authorize(oAuthMessage.getRequest(), oAuthMessage.getResponse());
    }

    private boolean isFlowStateIncomplete(OAuthMessage oAuthMessage) {

        return AuthenticatorFlowStatus.INCOMPLETE.equals(oAuthMessage.getFlowStatus());
    }

    private Response handleIncompleteFlow(OAuthMessage oAuthMessage, CommonAuthResponseWrapper responseWrapper)
            throws IOException, URISyntaxException, URLBuilderException {

        if (responseWrapper.isRedirect()) {
            return Response.status(HttpServletResponse.SC_FOUND)
                    .location(buildURI(responseWrapper.getRedirectURL())).build();
        } else {
            return Response.status(HttpServletResponse.SC_OK).entity(responseWrapper.getContent()).build();
        }
    }

    private boolean isAuthFlowStateExists(OAuthMessage oAuthMessage) {

        return oAuthMessage.getFlowStatus() != null;
    }

    private void invokeCommonauthFlow(OAuthMessage oAuthMessage, CommonAuthResponseWrapper responseWrapper)
            throws ServletException, IOException {

        CommonAuthenticationHandler commonAuthenticationHandler = new CommonAuthenticationHandler();
        commonAuthenticationHandler.doGet(oAuthMessage.getRequest(), responseWrapper);
    }

    /**
     * This method use to call authentication framework directly via API other than using HTTP redirects.
     * Sending wrapper request object to doGet method since other original request doesn't exist required parameters
     * Doesn't check SUCCESS_COMPLETED since taking decision with INCOMPLETE status
     *
     * @param type authenticator type
     * @throws URISyntaxException
     * @throws InvalidRequestParentException
     * @Param type OAuthMessage
     */
    private Response handleAuthFlowThroughFramework(OAuthMessage oAuthMessage, String type) throws URISyntaxException,
            InvalidRequestParentException {

        try {
            String sessionDataKey =
                    (String) oAuthMessage.getRequest().getAttribute(FrameworkConstants.SESSION_DATA_KEY);

            CommonAuthenticationHandler commonAuthenticationHandler = new CommonAuthenticationHandler();

            CommonAuthRequestWrapper requestWrapper = new CommonAuthRequestWrapper(oAuthMessage.getRequest());
            requestWrapper.setParameter(FrameworkConstants.SESSION_DATA_KEY, sessionDataKey);
            requestWrapper.setParameter(FrameworkConstants.RequestParams.TYPE, type);

            CommonAuthResponseWrapper responseWrapper = new CommonAuthResponseWrapper(oAuthMessage.getResponse());
            commonAuthenticationHandler.doGet(requestWrapper, responseWrapper);

            Object attribute = oAuthMessage.getRequest().getAttribute(FrameworkConstants.RequestParams.FLOW_STATUS);
            if (attribute != null) {
                if (attribute == AuthenticatorFlowStatus.INCOMPLETE) {

                    if (responseWrapper.isRedirect()) {
                        return Response.status(HttpServletResponse.SC_FOUND)
                                .location(buildURI(responseWrapper.getRedirectURL())).build();
                    } else {
                        return Response.status(HttpServletResponse.SC_OK).entity(responseWrapper.getContent()).build();
                    }
                } else {
                    return authorize(requestWrapper, responseWrapper);
                }
            } else {
                requestWrapper
                        .setAttribute(FrameworkConstants.RequestParams.FLOW_STATUS, AuthenticatorFlowStatus.UNKNOWN);
                return authorize(requestWrapper, responseWrapper);
            }
        } catch (ServletException | IOException | URLBuilderException e) {
            log.error("Error occurred while sending request to authentication framework.");
            return Response.status(HttpServletResponse.SC_INTERNAL_SERVER_ERROR).build();
        }
    }

    private URI buildURI(String redirectUrl) throws URISyntaxException, URLBuilderException {

        URI uri = new URI(redirectUrl);
        if (uri.isAbsolute()) {
            return uri;
        } else {
            return new URI(ServiceURLBuilder.create().addPath(redirectUrl).build().getAbsolutePublicURL());
        }
    }

    private String manageOIDCSessionState(OAuthMessage oAuthMessage,
                                          OIDCSessionState sessionStateObj, OAuth2Parameters oAuth2Parameters,
                                          String authenticatedUser, String redirectURL, SessionDataCacheEntry
                                                  sessionDataCacheEntry)  {

        HttpServletRequest request = oAuthMessage.getRequest();
        HttpServletResponse response = oAuthMessage.getResponse();
        Cookie opBrowserStateCookie = OIDCSessionManagementUtil.getOPBrowserStateCookie(request);
        if (sessionStateObj.isAuthenticated()) { // successful user authentication
            if (opBrowserStateCookie == null) { // new browser session
                if (log.isDebugEnabled()) {
                    log.debug("User authenticated. Initiate OIDC browser session.");
                }
                opBrowserStateCookie = OIDCSessionManagementUtil.
                        addOPBrowserStateCookie(response, request, oAuth2Parameters.getLoginTenantDomain(),
                                sessionDataCacheEntry.getSessionContextIdentifier());
                // Adding sid claim in the IDtoken to OIDCSessionState class.
                storeSidClaim(oAuthMessage, sessionStateObj, redirectURL);
                sessionStateObj.setAuthenticatedUser(authenticatedUser);
                sessionStateObj.addSessionParticipant(oAuth2Parameters.getClientId());
                OIDCSessionManagementUtil.getSessionManager().storeOIDCSessionState(opBrowserStateCookie.getValue(),
                        sessionStateObj, oAuth2Parameters.getLoginTenantDomain());
            } else { // browser session exists
                OIDCSessionState previousSessionState =
                        OIDCSessionManagementUtil.getSessionManager().getOIDCSessionState
                                (opBrowserStateCookie.getValue(), oAuth2Parameters.getLoginTenantDomain());
                if (previousSessionState != null) {
                    if (!previousSessionState.getSessionParticipants().contains(oAuth2Parameters.getClientId())) {
                        // User is authenticated to a new client. Restore browser session state
                        if (log.isDebugEnabled()) {
                            log.debug("User is authenticated to a new client. Restore browser session state.");
                        }
                        String oldOPBrowserStateCookieId = opBrowserStateCookie.getValue();
                        opBrowserStateCookie = OIDCSessionManagementUtil
                                .addOPBrowserStateCookie(response, request, oAuth2Parameters.getLoginTenantDomain(),
                                        sessionDataCacheEntry.getSessionContextIdentifier());
                        String newOPBrowserStateCookieId = opBrowserStateCookie.getValue();
                        previousSessionState.addSessionParticipant(oAuth2Parameters.getClientId());
                        OIDCSessionManagementUtil.getSessionManager().restoreOIDCSessionState
                                (oldOPBrowserStateCookieId, newOPBrowserStateCookieId, previousSessionState,
                                        oAuth2Parameters.getLoginTenantDomain());
                    }
                    // Storing the oidc session id.
                    storeSidClaim(oAuthMessage, previousSessionState, redirectURL);
                } else {
                    log.warn("No session state found for the received Session ID : " + opBrowserStateCookie.getValue());
                    if (log.isDebugEnabled()) {
                        log.debug("Restore browser session state.");
                    }
                    opBrowserStateCookie = OIDCSessionManagementUtil
                            .addOPBrowserStateCookie(response, request, oAuth2Parameters.getLoginTenantDomain(),
                                    sessionDataCacheEntry.getSessionContextIdentifier());
                    sessionStateObj.setAuthenticatedUser(authenticatedUser);
                    sessionStateObj.addSessionParticipant(oAuth2Parameters.getClientId());
                    storeSidClaim(oAuthMessage, sessionStateObj, redirectURL);
                    OIDCSessionManagementUtil.getSessionManager().storeOIDCSessionState(opBrowserStateCookie.getValue(),
                            sessionStateObj, oAuth2Parameters.getLoginTenantDomain());
                }
            }
        }

        if (sessionStateObj.isAddSessionState()) {
            String sessionStateParam = OIDCSessionManagementUtil.getSessionStateParam(oAuth2Parameters.getClientId(),
                    oAuth2Parameters.getRedirectURI(),
                    opBrowserStateCookie == null ?
                            null :
                            opBrowserStateCookie.getValue());
            redirectURL = OIDCSessionManagementUtil.addSessionStateToURL(redirectURL, sessionStateParam,
                    oAuth2Parameters.getResponseType());

            if (RESPONSE_MODE_FORM_POST.equals(oAuth2Parameters.getResponseMode()) && isJSON(redirectURL)) {
                return sessionStateParam;
            }
        }

        return redirectURL;
    }

    private String appendAuthenticatedIDPs(SessionDataCacheEntry sessionDataCacheEntry, String redirectURL) {

        if (sessionDataCacheEntry != null) {
            String authenticatedIdPs = sessionDataCacheEntry.getAuthenticatedIdPs();

            if (authenticatedIdPs != null && !authenticatedIdPs.isEmpty()) {
                try {
                    String idpAppendedRedirectURL = redirectURL + "&AuthenticatedIdPs=" + URLEncoder.encode
                            (authenticatedIdPs, "UTF-8");
                    return idpAppendedRedirectURL;
                } catch (UnsupportedEncodingException e) {
                    //this exception should not occur
                    log.error("Error while encoding the url", e);
                }
            }
        }
        return redirectURL;
    }

    /**
     * Associates the authentication method references done while logged into the session (if any) to the OAuth cache.
     * The SessionDataCacheEntry then will be used when getting "AuthenticationMethodReferences". Please see
     * <a href="https://tools.ietf.org/html/draft-ietf-oauth-amr-values-02" >draft-ietf-oauth-amr-values-02</a>.
     *
     * @param resultFromLogin
     * @param cookie
     */
    private void associateAuthenticationHistory(SessionDataCacheEntry resultFromLogin, Cookie cookie) {

        SessionContext sessionContext = getSessionContext(cookie,
                resultFromLogin.getoAuth2Parameters().getLoginTenantDomain());
        if (sessionContext != null && sessionContext.getSessionAuthHistory() != null
                && sessionContext.getSessionAuthHistory().getHistory() != null) {
            List<String> authMethods = new ArrayList<>();
            for (AuthHistory authHistory : sessionContext.getSessionAuthHistory().getHistory()) {
                authMethods.add(authHistory.toTranslatableString());
            }
            resultFromLogin.getParamMap().put(OAuthConstants.AMR, authMethods.toArray(new String[authMethods.size()]));
        }
    }

    /**
     * Returns the SessionContext associated with the cookie, if there is a one.
     *
     * @param cookie
     * @param loginTenantDomain Login tenant domain.
     * @return the associate SessionContext or null.
     */
    private SessionContext getSessionContext(Cookie cookie, String loginTenantDomain) {

        if (cookie != null) {
            String sessionContextKey = DigestUtils.sha256Hex(cookie.getValue());
            return FrameworkUtils.getSessionContextFromCache(sessionContextKey, loginTenantDomain);
        }
        return null;
    }

    /**
     * Gets the last authenticated value from the commonAuthId cookie
     *
     * @param cookie CommonAuthId cookie
     * @param loginTenantDomain Login tenant domain
     * @return the last authenticated timestamp
     */
    private long getAuthenticatedTimeFromCommonAuthCookie(Cookie cookie, String loginTenantDomain) {

        long authTime = 0;
        if (cookie != null) {
            String sessionContextKey = DigestUtils.sha256Hex(cookie.getValue());
            SessionContext sessionContext = FrameworkUtils.getSessionContextFromCache(sessionContextKey,
                    loginTenantDomain);
            if (sessionContext != null) {
                if (sessionContext.getProperty(FrameworkConstants.UPDATED_TIMESTAMP) != null) {
                    authTime = Long.parseLong(
                            sessionContext.getProperty(FrameworkConstants.UPDATED_TIMESTAMP).toString());
                } else {
                    authTime = Long.parseLong(
                            sessionContext.getProperty(FrameworkConstants.CREATED_TIMESTAMP).toString());
                }
            }
        }
        return authTime;
    }

    /**
     * Build OAuthProblem exception based on error details sent by the Framework as properties in the
     * AuthenticationResult object.
     *
     * @param authenticationResult
     * @return
     */
    OAuthProblemException buildOAuthProblemException(AuthenticationResult authenticationResult,
                                                     OAuthErrorDTO oAuthErrorDTO) {

        String errorCode = String.valueOf(authenticationResult.getProperty(FrameworkConstants.AUTH_ERROR_CODE));
        if (IdentityUtil.isBlank(errorCode)) {
            // If there is no custom error code sent from framework we set our default error code.
            errorCode = OAuth2ErrorCodes.LOGIN_REQUIRED;
        }

        String errorMessage = String.valueOf(authenticationResult.getProperty(FrameworkConstants.AUTH_ERROR_MSG));
        if (IdentityUtil.isBlank(errorMessage)) {
            if (oAuthErrorDTO != null && StringUtils.isNotBlank(oAuthErrorDTO.getErrorDescription())) {
                // If there is a custom message from responseTypeHandler we set that as error message.
                errorMessage = oAuthErrorDTO.getErrorDescription();
            } else {
                // If there is no custom error message sent from framework we set our default error message.
                errorMessage = DEFAULT_ERROR_MSG_FOR_FAILURE;
            }
        }

        String errorUri = String.valueOf(authenticationResult.getProperty(FrameworkConstants.AUTH_ERROR_URI));
        if (IdentityUtil.isBlank(errorUri)) {
            if (oAuthErrorDTO != null && StringUtils.isNotBlank(oAuthErrorDTO.getErrorURI())) {
                // If there is a custom message from responseTypeHandler we set that as error message.
                return OAuthProblemException.error(errorCode, errorMessage).uri(oAuthErrorDTO.getErrorURI());
            } else {
                // If there is no custom error URI we set just error code and message.
                return OAuthProblemException.error(errorCode, errorMessage);
            }
        } else {
            // If there is a error uri sent in the authentication result we add that to the exception.
            return OAuthProblemException.error(errorCode, errorMessage).uri(errorUri);
        }
    }

    /**
     * Store sessionID using the redirect URl.
     * @param oAuthMessage
     * @param sessionState
     * @param redirectURL
     */
    private void storeSidClaim(OAuthMessage oAuthMessage, OIDCSessionState sessionState, String redirectURL) {

        if (redirectURL.contains(ID_TOKEN)) {
            String oidcSessionState = (String) oAuthMessage.getProperty(OIDC_SESSION_ID);
            sessionState.setSidClaim(oidcSessionState);
        } else if (redirectURL.contains(ACCESS_CODE)) {
            setSidToSessionState(sessionState);
            addToBCLogoutSessionToOAuthMessage(oAuthMessage, sessionState.getSidClaim());
        }
    }

    /**
     * Generate sessionID if there is no sessionID otherwise get sessionId from Session State
     *
     * @param sessionState
     */
    private void setSidToSessionState(OIDCSessionState sessionState) {

        String sessionId = sessionState.getSidClaim();
        if (sessionId == null) {
            // Generating sid claim for authorization code flow.
            sessionId = UUID.randomUUID().toString();
            sessionState.setSidClaim(sessionId);
        }
    }

    /**
     * Return true if the id token is encrypted.
     *
     * @param idToken String JWT ID token.
     * @return Boolean state of encryption.
     */
    private boolean isIDTokenEncrypted(String idToken) {
        // Encrypted ID token contains 5 base64 encoded components separated by periods.
        return StringUtils.countMatches(idToken, ".") == 4;
    }

    /**
     * Get id token from redirect Url fragment.
     *
     * @param redirectURL
     * @return
     * @throws URISyntaxException
     */
    private String getIdTokenFromRedirectURL(String redirectURL) throws URISyntaxException {

        String fragment = new URI(redirectURL).getFragment();
        Map<String, String> output = new HashMap<>();
        String[] keys = fragment.split("&");
        for (String key : keys) {
            String[] values = key.split("=");
            output.put(values[0], (values.length > 1 ? values[1] : ""));
            if (ID_TOKEN.equals(values[0])) {
                break;
            }
        }
        String idToken = output.get(ID_TOKEN);
        return idToken;
    }

    /**
     * Get AuthorizationCode from redirect Url query parameters.
     *
     * @param redirectURL
     * @return
     * @throws URISyntaxException
     */
    private String getAuthCodeFromRedirectURL(String redirectURL) throws URISyntaxException {

        String authCode = null;
        List<NameValuePair> queryParameters = new URIBuilder(redirectURL).getQueryParams();
        for (NameValuePair param : queryParameters) {
            if ((ACCESS_CODE).equals(param.getName())) {
                authCode = param.getValue();
            }
        }
        return authCode;
    }

    /**
     * Store Authorization Code and SessionID for back-channel logout in the cache.
     *
     * @param oAuthMessage
     * @param sessionId
     */
    private void addToBCLogoutSessionToOAuthMessage(OAuthMessage oAuthMessage, String sessionId) {

        AuthorizationGrantCacheEntry entry = oAuthMessage.getAuthorizationGrantCacheEntry();
        if (entry == null) {
            log.debug("Authorization code is not found in the redirect URL");
            return;
        }
        entry.setOidcSessionId(sessionId);
    }

    private void setSPAttributeToRequest(HttpServletRequest req, String spName, String tenantDomain) {

        req.setAttribute(REQUEST_PARAM_SP, spName);
        req.setAttribute(TENANT_DOMAIN, tenantDomain);
    }

    /**
     * Return OAuth2Parameters retrieved from OAuthMessage.
     * @param oAuthMessage
     * @return OAuth2Parameters
     */
    private OAuth2Parameters getOAuth2ParamsFromOAuthMessage(OAuthMessage oAuthMessage) {

        OAuth2Parameters oAuth2Parameters = new OAuth2Parameters();
        if (oAuthMessage.getSessionDataCacheEntry() != null) {
            oAuth2Parameters = getOauth2Params(oAuthMessage);
        }
        return oAuth2Parameters;
    }
}
