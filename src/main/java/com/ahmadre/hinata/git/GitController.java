package com.ahmadre.hinata.git;

import com.ahmadre.hinata.auth.CurrentUser;
import com.ahmadre.hinata.project.Project;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Git integration REST surface. Connection + automation live under a project
 * ({@code /projects/{id}/git/...}); development information + PR actions live
 * under an issue key ({@code /issues/{key}/dev-info...}). Access control is
 * enforced in {@link GitService} (lead/admin to mutate a connection, member to
 * read dev-info or act on a PR).
 */
@Tag(name = "Git integration")
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class GitController {

	private final GitService git;
	private final CurrentUser currentUser;

	// ─────────────────────────── connection (per project) ───────────────────────────

	public record OAuthStartRequest(@NotBlank String provider) {
	}

	@PostMapping("/projects/{id}/git/oauth/start")
	public GitService.OAuthStart oauthStart(@PathVariable String id,
			@RequestBody @Valid OAuthStartRequest request) {
		return git.oauthStart(id, request.provider(), currentUser.require());
	}

	@GetMapping("/projects/{id}/git/owners")
	public List<GitService.OwnerDto> owners(@PathVariable String id, @RequestParam String provider) {
		return git.owners(id, provider, currentUser.require());
	}

	@GetMapping("/projects/{id}/git/repos")
	public List<GitService.RepoDto> repos(@PathVariable String id, @RequestParam String provider,
			@RequestParam String owner, @RequestParam(required = false) String q) {
		return git.repos(id, provider, owner, q, currentUser.require());
	}

	public record ConnectRequest(@NotBlank String provider, @NotBlank String owner, @NotBlank String repo) {
	}

	@PostMapping("/projects/{id}/git/connect")
	public Project connect(@PathVariable String id, @RequestBody @Valid ConnectRequest request) {
		return git.connect(id, request.provider(), request.owner(), request.repo(), currentUser.require());
	}

	public record ConnectTokenRequest(@NotBlank String repoUrl, @NotBlank String token) {
	}

	@PostMapping("/projects/{id}/git/connect-token")
	public Project connectToken(@PathVariable String id, @RequestBody @Valid ConnectTokenRequest request) {
		return git.connectToken(id, request.repoUrl(), request.token(), currentUser.require());
	}

	@DeleteMapping("/projects/{id}/git")
	public Project disconnect(@PathVariable String id) {
		return git.disconnect(id, currentUser.require());
	}

	@PostMapping("/projects/{id}/git/resync")
	public Project resync(@PathVariable String id) {
		return git.resync(id, currentUser.require());
	}

	@PatchMapping("/projects/{id}/git/automation")
	public Project automation(@PathVariable String id, @RequestBody Project.Automation automation) {
		return git.setAutomation(id, automation, currentUser.require());
	}

	public record BranchTemplateRequest(String branchTemplate) {
	}

	@PatchMapping("/projects/{id}/git/branch-template")
	public Project branchTemplate(@PathVariable String id, @RequestBody @Valid BranchTemplateRequest request) {
		return git.setBranchTemplate(id, request.branchTemplate(), currentUser.require());
	}

	// ─────────────────────────── development info (per issue) ───────────────────────────

	@GetMapping("/issues/{key}/dev-info")
	public GitService.DevInfoResponse devInfo(@PathVariable String key) {
		return git.devInfo(key, currentUser.require());
	}

	@PostMapping("/issues/{key}/dev-info/prs/{number}/merge")
	public GitService.PrActionResponse mergePr(@PathVariable String key, @PathVariable int number) {
		return git.mergePr(key, number, currentUser.require());
	}

	@PostMapping("/issues/{key}/dev-info/prs/{number}/ready")
	public GitService.PrActionResponse readyPr(@PathVariable String key, @PathVariable int number) {
		return git.readyPr(key, number, currentUser.require());
	}
}
