package hn.asta.hivora.auth.sso;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.security.oauth2.client.web.AuthorizationRequestRepository;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.time.Duration;
import java.util.Base64;

/**
 * Stores the pending {@link OAuth2AuthorizationRequest} in a browser cookie
 * instead of the HTTP session. The default session storage breaks in exactly
 * the situations a self-hosted SSO flow runs into: in-memory sessions die with
 * a restart, and the IdP's cross-site redirect back to the callback does not
 * carry {@code SameSite=Lax} session cookies when it arrives as a POST or via
 * script navigation. The cookie is {@code HttpOnly} and short-lived; it only
 * ever protects the requester's own login handshake (state + PKCE verifier).
 */
@Slf4j
@Component
public class CookieAuthorizationRequestRepository
		implements AuthorizationRequestRepository<OAuth2AuthorizationRequest> {

	static final String COOKIE_NAME = "HIVORA_OAUTH_REQ";
	private static final Duration TTL = Duration.ofMinutes(10);

	@Override
	public OAuth2AuthorizationRequest loadAuthorizationRequest(HttpServletRequest request) {
		if (request.getCookies() == null) {
			return null;
		}
		for (Cookie cookie : request.getCookies()) {
			if (COOKIE_NAME.equals(cookie.getName())) {
				return deserialize(cookie.getValue());
			}
		}
		return null;
	}

	@Override
	public void saveAuthorizationRequest(OAuth2AuthorizationRequest authorizationRequest,
			HttpServletRequest request, HttpServletResponse response) {
		if (authorizationRequest == null) {
			expire(request, response);
			return;
		}
		ResponseCookie cookie = builder(request, serialize(authorizationRequest), TTL);
		response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
	}

	@Override
	public OAuth2AuthorizationRequest removeAuthorizationRequest(HttpServletRequest request,
			HttpServletResponse response) {
		OAuth2AuthorizationRequest authorizationRequest = loadAuthorizationRequest(request);
		expire(request, response);
		return authorizationRequest;
	}

	private void expire(HttpServletRequest request, HttpServletResponse response) {
		response.addHeader(HttpHeaders.SET_COOKIE,
				builder(request, "", Duration.ZERO).toString());
	}

	/**
	 * {@code SameSite=None} (with {@code Secure}) so the cookie is presented on
	 * the cross-site redirect back from the IdP regardless of method; plain
	 * {@code Lax} on insecure local HTTP where {@code None} is not accepted.
	 */
	private ResponseCookie builder(HttpServletRequest request, String value, Duration maxAge) {
		boolean secure = request.isSecure();
		return ResponseCookie.from(COOKIE_NAME, value)
				.httpOnly(true)
				.secure(secure)
				.path("/")
				.maxAge(maxAge)
				.sameSite(secure ? "None" : "Lax")
				.build();
	}

	private String serialize(OAuth2AuthorizationRequest authorizationRequest) {
		try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
				ObjectOutputStream out = new ObjectOutputStream(bytes)) {
			out.writeObject(authorizationRequest);
			out.flush();
			return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes.toByteArray());
		}
		catch (Exception ex) {
			throw new IllegalStateException("Cannot serialize authorization request", ex);
		}
	}

	private OAuth2AuthorizationRequest deserialize(String value) {
		try (ObjectInputStream in = new ObjectInputStream(
				new ByteArrayInputStream(Base64.getUrlDecoder().decode(value)))) {
			return (OAuth2AuthorizationRequest) in.readObject();
		}
		catch (Exception ex) {
			log.warn("Discarding unreadable authorization request cookie: {}", ex.getMessage());
			return null;
		}
	}
}
