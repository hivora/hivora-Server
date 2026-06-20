package com.ahmadre.hinata.me;

import com.ahmadre.hinata.auth.CurrentUser;
import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.storage.StorageService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.time.Duration;
import java.util.Map;

/**
 * Profile-picture management. Kept separate from {@link MeController} so the
 * avatar/image concern stays decoupled. Upload/delete are on {@code /me}
 * (authenticated, owner-only); the read endpoint is a public, cacheable bytes
 * proxy ({@code /users/{id}/avatar}) so an {@code <img>} can load it without a
 * bearer token (same pattern as the org-logo proxy).
 */
@Tag(name = "Account")
@RestController
@RequiredArgsConstructor
public class AvatarController {

	private final AvatarService avatars;
	private final CurrentUser currentUser;

	@Operation(summary = "Upload my profile picture (compressed server-side)")
	@PostMapping(value = "/api/v1/me/avatar", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	public Map<String, String> upload(@RequestParam("file") MultipartFile file) {
		return Map.of("avatarUrl", avatars.store(currentUser.require(), file));
	}

	@Operation(summary = "Remove my profile picture")
	@DeleteMapping("/api/v1/me/avatar")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void remove() {
		avatars.remove(currentUser.require());
	}

	@Operation(summary = "Fetch a user's profile picture (public, cacheable)")
	@SecurityRequirements
	@GetMapping("/api/v1/users/{id}/avatar")
	public ResponseEntity<byte[]> get(@PathVariable String id) {
		StorageService.StoredObject avatar = avatars.load(id)
				.orElseThrow(() -> ApiException.notFound("avatar"));
		return ResponseEntity.ok()
				.contentType(MediaType.parseMediaType(avatar.contentType()))
				.cacheControl(CacheControl.maxAge(Duration.ofDays(7)).cachePublic())
				.body(avatar.data());
	}
}
