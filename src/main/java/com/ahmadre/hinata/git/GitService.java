package com.ahmadre.hinata.git;

import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.config.HinataProperties;
import com.ahmadre.hinata.issue.Issue;
import com.ahmadre.hinata.issue.IssueService;
import com.ahmadre.hinata.project.Project;
import com.ahmadre.hinata.project.ProjectService;
import com.ahmadre.hinata.user.User;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Git integration — <strong>per project</strong>. Each project owns its provider
 * + repository connection and its own automation rules, expressed against that
 * project's own {@link Project.WorkflowState} ids. Nothing here is global.
 *
 * <p>OAuth is brokered server-side: the client never receives a provider token
 * (see {@link TokenCipher} + {@code @JsonIgnore} on {@link Project.Git}). Until
 * real provider app credentials + a public webhook URL are configured, the
 * account graph (owners/repos) and development information are emulated /
 * seeded; the endpoint surface, storage and client behaviour are identical, so
 * wiring real credentials later is a pure config change.
 */
@Service
@RequiredArgsConstructor
public class GitService {

	private static final Logger log = LoggerFactory.getLogger(GitService.class);

	private static final Set<String> PROVIDERS = Set.of("github", "gitlab", "bitbucket");
	private static final String DEFAULT_TEMPLATE = "{key}-{summary}";

	private final ProjectService projects;
	private final IssueService issues;
	private final GitDevInfoRepository devInfos;
	private final TokenCipher cipher;
	private final HinataProperties properties;

	// ─────────────────────────── connection (per project) ───────────────────────────

	/** Kicks off the OAuth app flow; returns the URL to open (emulated when no creds). */
	public OAuthStart oauthStart(String projectId, String provider, User user) {
		Project project = requireLead(projectId, user);
		validateProvider(provider);
		boolean configured = clientConfigured(provider);
		return new OAuthStart(buildAuthorizeUrl(provider, project, configured), !configured);
	}

	/** Owners (org / group / workspace) the account exposes for {@code provider}. */
	public List<OwnerDto> owners(String projectId, String provider, User user) {
		requireLead(projectId, user);
		validateProvider(provider);
		return EMULATED_OWNERS.getOrDefault(provider, List.of());
	}

	/** Repositories under {@code owner}, filtered by an optional query. */
	public List<RepoDto> repos(String projectId, String provider, String owner, String query, User user) {
		requireLead(projectId, user);
		validateProvider(provider);
		String needle = query == null ? "" : query.toLowerCase().trim();
		return EMULATED_REPOS.stream()
				.filter(r -> needle.isEmpty() || r.name().toLowerCase().contains(needle))
				.toList();
	}

	/** Binds the chosen repo to the project (would register webhooks in a real setup). */
	public Project connect(String projectId, String provider, String owner, String repo, User user) {
		Project project = requireLead(projectId, user);
		validateProvider(provider);
		if (isBlank(owner) || isBlank(repo)) {
			throw ApiException.badRequest("error.git.repoRequired");
		}
		Instant now = Instant.now();
		project.setGit(Project.Git.builder()
				.provider(provider)
				.owner(owner.trim())
				.repo(repo.trim())
				.defaultBranch("main")
				.connectedBy(user.getId())
				.connectedAt(now)
				.lastSyncAt(now)
				.method("oauth")
				.branchTemplate(DEFAULT_TEMPLATE)
				.automation(defaultAutomation(project))
				.encryptedToken(cipher.encrypt(emulatedToken(provider)))
				.build());
		return projects.save(project);
	}

	/** Self-managed fallback — store repo URL + PAT (encrypted, server-side). */
	public Project connectToken(String projectId, String repoUrl, String token, User user) {
		Project project = requireLead(projectId, user);
		if (isBlank(repoUrl) || isBlank(token)) {
			throw ApiException.badRequest("error.git.tokenRequired");
		}
		String provider = detectProvider(repoUrl);
		String[] ownerRepo = ownerRepoFromUrl(repoUrl, project.getKey());
		Instant now = Instant.now();
		project.setGit(Project.Git.builder()
				.provider(provider)
				.owner(ownerRepo[0])
				.repo(ownerRepo[1])
				.defaultBranch("main")
				.connectedBy(user.getId())
				.connectedAt(now)
				.lastSyncAt(now)
				.method("token")
				.branchTemplate(DEFAULT_TEMPLATE)
				.automation(defaultAutomation(project))
				.encryptedToken(cipher.encrypt(token))
				.build());
		return projects.save(project);
	}

	public Project disconnect(String projectId, User user) {
		Project project = requireLead(projectId, user);
		project.setGit(null);
		return projects.save(project);
	}

	public Project resync(String projectId, User user) {
		Project project = requireLead(projectId, user);
		requireConnected(project);
		project.getGit().setLastSyncAt(Instant.now());
		return projects.save(project);
	}

	public Project setAutomation(String projectId, Project.Automation incoming, User user) {
		Project project = requireLead(projectId, user);
		requireConnected(project);
		Project.Automation current = project.getGit().getAutomation();
		Project.Automation merged = Project.Automation.builder()
				.branchCreated(orElse(incoming == null ? null : incoming.getBranchCreated(), current.getBranchCreated()))
				.commitPushed(orElse(incoming == null ? null : incoming.getCommitPushed(), current.getCommitPushed()))
				.prOpened(orElse(incoming == null ? null : incoming.getPrOpened(), current.getPrOpened()))
				.prMerged(orElse(incoming == null ? null : incoming.getPrMerged(), current.getPrMerged()))
				.smartCommits(incoming != null && incoming.isSmartCommits())
				.build();
		validateAutomation(project, merged);
		project.getGit().setAutomation(merged);
		return projects.save(project);
	}

	public Project setBranchTemplate(String projectId, String template, User user) {
		Project project = requireLead(projectId, user);
		requireConnected(project);
		String cleaned = isBlank(template) ? DEFAULT_TEMPLATE : template.trim();
		project.getGit().setBranchTemplate(cleaned);
		return projects.save(project);
	}

	/**
	 * Default automation seeded on connect: all rules <em>off</em>, smart commits
	 * on, and each rule's target pre-pointed at a sensible state <em>in this
	 * project's workflow</em> (In Progress for branch/commit, In Review for a PR
	 * opened, the first resolved state for a PR merged) so enabling a rule already
	 * points somewhere valid.
	 */
	public Project.Automation defaultAutomation(Project project) {
		return Project.Automation.builder()
				.branchCreated(Project.Rule.builder().on(false).toStateId(stateIdContaining(project, "progress")).build())
				.commitPushed(Project.Rule.builder().on(false).toStateId(stateIdContaining(project, "progress")).build())
				.prOpened(Project.Rule.builder().on(false).toStateId(stateIdContaining(project, "review")).build())
				.prMerged(Project.Rule.builder().on(false).toStateId(resolvedStateId(project)).build())
				.smartCommits(true)
				.build();
	}

	// ─────────────────────────── development info (per issue) ───────────────────────────

	public DevInfoResponse devInfo(String issueKey, User user) {
		Issue issue = issues.get(issueKey);
		Project project = projects.get(issue.getProjectId());
		projects.assertMember(project, user);
		if (project.getGit() == null) {
			return DevInfoResponse.notConnected();
		}
		GitDevInfo info = devInfos.findByIssueKeyIgnoreCase(issue.getReadableId()).orElse(null);
		return DevInfoResponse.connected(project.getGit(), info);
	}

	/** Merge a PR/MR from the Development panel; applies the {@code prMerged} rule. */
	public PrActionResponse mergePr(String issueKey, int number, User user) {
		return transitionPr(issueKey, number, "MERGED", user);
	}

	/** Mark a draft PR/MR ready for review; applies the {@code prOpened} rule. */
	public PrActionResponse readyPr(String issueKey, int number, User user) {
		return transitionPr(issueKey, number, "OPEN", user);
	}

	private PrActionResponse transitionPr(String issueKey, int number, String newState, User user) {
		Issue issue = issues.get(issueKey);
		Project project = projects.get(issue.getProjectId());
		projects.assertMember(project, user);
		requireConnected(project);
		GitDevInfo info = devInfos.findByIssueKeyIgnoreCase(issue.getReadableId())
				.orElseThrow(() -> ApiException.notFound("git.devInfo"));
		GitDevInfo.PullRequest pr = info.getPrs().stream()
				.filter(p -> p.getNumber() == number)
				.findFirst()
				.orElseThrow(() -> ApiException.notFound("git.pullRequest"));
		pr.setState(newState);
		info.setUpdatedAt(Instant.now());
		devInfos.save(info);

		Issue updated = issue;
		Project.Automation auto = project.getGit().getAutomation();
		if (auto != null) {
			if ("MERGED".equals(newState) && isOn(auto.getPrMerged())) {
				updated = applyTransition(project, issue, auto.getPrMerged().getToStateId(), user);
			}
			else if ("OPEN".equals(newState) && isOn(auto.getPrOpened())) {
				updated = applyTransition(project, issue, auto.getPrOpened().getToStateId(), user);
			}
		}
		return new PrActionResponse(DevInfoResponse.connected(project.getGit(), info), updated);
	}

	// ─────────────────────────── smart commits (§5) ───────────────────────────

	/**
	 * Applies every smart-commit command found in a commit message, honouring the
	 * per-project {@code smartCommits} toggle. This is the single real code path a
	 * push webhook (or the demo seeder) drives — {@code #comment} adds a comment,
	 * {@code #time} logs work, any other {@code #word} transitions the issue.
	 */
	public void applySmartCommits(String message, User actor) {
		for (SmartCommitParser.Command command : SmartCommitParser.parse(message)) {
			Issue issue;
			try {
				issue = issues.get(command.issueKey());
			}
			catch (ApiException unknownKey) {
				continue; // key referenced in the commit points at no issue — ignore
			}
			Project project = projects.get(issue.getProjectId());
			Project.Git git = project.getGit();
			if (git == null || git.getAutomation() == null || !git.getAutomation().isSmartCommits()) {
				continue;
			}
			try {
				switch (command.type()) {
					case COMMENT -> issues.addComment(issue.getId(), command.value(), actor);
					case TIME -> {
						int minutes = SmartCommitParser.minutes(command.value());
						if (minutes > 0) {
							issues.update(issue.getId(),
									i -> i.setSpentMinutes(i.getSpentMinutes() + minutes), actor);
						}
					}
					case TRANSITION -> {
						String state = matchState(project, command.value());
						if (state != null) {
							issues.update(issue.getId(), i -> i.setState(state), actor);
						}
					}
				}
			}
			catch (RuntimeException e) {
				log.warn("[git] smart-commit '{}' on {} skipped: {}",
						command.type(), command.issueKey(), e.getMessage());
			}
		}
	}

	// ─────────────────────────── internals ───────────────────────────

	private Issue applyTransition(Project project, Issue issue, String toStateId, User actor) {
		if (isBlank(toStateId)) {
			return issue;
		}
		String name = stateNameById(project, toStateId);
		if (name == null || name.equals(issue.getState())) {
			return issue; // rule points at a state that no longer exists, or a no-op
		}
		return issues.update(issue.getId(), i -> i.setState(name), actor);
	}

	private Project requireLead(String projectId, User user) {
		Project project = projects.get(projectId);
		projects.assertLeadOrAdmin(project, user);
		return project;
	}

	private void requireConnected(Project project) {
		if (project.getGit() == null) {
			throw ApiException.badRequest("error.git.notConnected");
		}
	}

	private void validateProvider(String provider) {
		if (provider == null || !PROVIDERS.contains(provider)) {
			throw ApiException.badRequest("error.git.unknownProvider", String.valueOf(provider));
		}
	}

	private void validateAutomation(Project project, Project.Automation automation) {
		Set<String> ids = project.getWorkflowStates().stream()
				.map(Project.WorkflowState::getId)
				.collect(Collectors.toSet());
		checkRule(automation.getBranchCreated(), ids);
		checkRule(automation.getCommitPushed(), ids);
		checkRule(automation.getPrOpened(), ids);
		checkRule(automation.getPrMerged(), ids);
	}

	private void checkRule(Project.Rule rule, Set<String> stateIds) {
		if (rule == null) {
			return;
		}
		if (!isBlank(rule.getToStateId()) && !stateIds.contains(rule.getToStateId())) {
			throw ApiException.badRequest("error.git.unknownState");
		}
		if (rule.isOn() && isBlank(rule.getToStateId())) {
			throw ApiException.badRequest("error.git.ruleNeedsState");
		}
	}

	private String stateIdContaining(Project project, String needle) {
		for (Project.WorkflowState s : project.getWorkflowStates()) {
			if (s.getName().toLowerCase().contains(needle)) {
				return s.getId();
			}
		}
		return project.getWorkflowStates().isEmpty() ? null : project.getWorkflowStates().get(0).getId();
	}

	private String resolvedStateId(Project project) {
		List<String> resolved = project.getResolvedStates();
		if (resolved != null && !resolved.isEmpty()) {
			for (Project.WorkflowState s : project.getWorkflowStates()) {
				if (s.getName().equalsIgnoreCase(resolved.get(0))) {
					return s.getId();
				}
			}
		}
		return stateIdContaining(project, "done");
	}

	private String stateNameById(Project project, String id) {
		for (Project.WorkflowState s : project.getWorkflowStates()) {
			if (s.getId().equals(id)) {
				return s.getName();
			}
		}
		return null;
	}

	/** Matches a smart-commit transition word against a workflow state name. */
	private String matchState(Project project, String word) {
		String needle = word.replaceAll("[^a-z0-9]", "");
		for (Project.WorkflowState s : project.getWorkflowStates()) {
			if (normalize(s.getName()).equals(needle)) {
				return s.getName();
			}
		}
		for (Project.WorkflowState s : project.getWorkflowStates()) {
			if (normalize(s.getName()).contains(needle)) {
				return s.getName();
			}
		}
		return null;
	}

	private static String normalize(String s) {
		return s.toLowerCase().replaceAll("[^a-z0-9]", "");
	}

	private boolean clientConfigured(String provider) {
		HinataProperties.GitIntegration g = properties.getGitIntegration();
		return switch (provider) {
			case "github" -> !isBlank(g.getGithubClientId());
			case "gitlab" -> !isBlank(g.getGitlabClientId());
			case "bitbucket" -> !isBlank(g.getBitbucketClientId());
			default -> false;
		};
	}

	private String clientId(String provider) {
		HinataProperties.GitIntegration g = properties.getGitIntegration();
		return switch (provider) {
			case "github" -> g.getGithubClientId();
			case "gitlab" -> g.getGitlabClientId();
			case "bitbucket" -> g.getBitbucketClientId();
			default -> "";
		};
	}

	private String buildAuthorizeUrl(String provider, Project project, boolean configured) {
		String state = project.getId();
		if (!configured) {
			// No real app registered — the client detects `emulated` and skips the
			// browser hand-off, proceeding straight to owner/repo picking.
			return properties.webBase() + "/git/authorize?provider=" + enc(provider)
					+ "&project=" + enc(project.getId()) + "&emulated=1";
		}
		String redirect = properties.getBaseUrl() + "/api/v1/projects/" + project.getId() + "/git/oauth/callback";
		return switch (provider) {
			case "github" -> "https://github.com/login/oauth/authorize?client_id=" + enc(clientId(provider))
					+ "&redirect_uri=" + enc(redirect) + "&scope=" + enc("repo read:org")
					+ "&state=" + enc(state);
			case "gitlab" -> "https://gitlab.com/oauth/authorize?client_id=" + enc(clientId(provider))
					+ "&redirect_uri=" + enc(redirect) + "&response_type=code"
					+ "&scope=" + enc("api read_repository write_repository") + "&state=" + enc(state);
			case "bitbucket" -> "https://bitbucket.org/site/oauth2/authorize?client_id=" + enc(clientId(provider))
					+ "&response_type=code&state=" + enc(state);
			default -> throw ApiException.badRequest("error.git.unknownProvider", provider);
		};
	}

	private static String emulatedToken(String provider) {
		return provider + "-emulated-" + UUID.randomUUID();
	}

	private static String detectProvider(String url) {
		String u = url.toLowerCase();
		if (u.contains("gitlab")) {
			return "gitlab";
		}
		if (u.contains("bitbucket")) {
			return "bitbucket";
		}
		return "github";
	}

	private static String[] ownerRepoFromUrl(String url, String projectKey) {
		String cleaned = url.replaceAll("^https?://", "").replaceAll("\\.git$", "");
		String[] parts = cleaned.split("/");
		String repo = parts.length > 0 ? parts[parts.length - 1] : projectKey.toLowerCase();
		String owner = parts.length > 2 ? parts[parts.length - 2] : "self-managed";
		return new String[] { owner, repo };
	}

	private static boolean isOn(Project.Rule rule) {
		return rule != null && rule.isOn();
	}

	private static Project.Rule orElse(Project.Rule value, Project.Rule fallback) {
		return value != null ? value : (fallback != null ? fallback : Project.Rule.off());
	}

	private static boolean isBlank(String s) {
		return s == null || s.isBlank();
	}

	private static String enc(String value) {
		return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
	}

	// ─────────────────────────── emulated account graph + DTOs ───────────────────────────

	private static final Map<String, List<OwnerDto>> EMULATED_OWNERS = Map.of(
			"github", List.of(
					new OwnerDto("hinata-platform", "hinata-platform", "Organization", 12),
					new OwnerDto("rebar-ahmad", "rebar-ahmad", "Personal account", 34)),
			"gitlab", List.of(
					new OwnerDto("hinata-platform", "hinata-platform", "Group", 9),
					new OwnerDto("asta-hn", "asta-hn", "Group", 21)),
			"bitbucket", List.of(
					new OwnerDto("hinata", "hinata", "Workspace", 7)));

	private static final List<RepoDto> EMULATED_REPOS = List.of(
			new RepoDto("hinata-app", true, "Dart", "#00B4AB", "2m"),
			new RepoDto("hinata-api", true, "Kotlin", "#A97BFF", "11m"),
			new RepoDto("hinata-infra", true, "HCL", "#844FBA", "1h"),
			new RepoDto("hinata-web", false, "TypeScript", "#3178C6", "3h"),
			new RepoDto("design-tokens", false, "CSS", "#663399", "2d"),
			new RepoDto("handbook", false, "MDX", "#FCB32C", "5d"));

	public record OAuthStart(String authorizeUrl, boolean emulated) {
	}

	public record OwnerDto(String id, String name, String kind, int repos) {
	}

	public record RepoDto(String name, boolean priv, String lang, String langColor, String updated) {
	}

	public record PrActionResponse(DevInfoResponse devInfo, Issue issue) {
	}

	/** What the client reads for an issue's Development summary. */
	public record DevInfoResponse(boolean connected, String provider, String owner, String repo,
			List<GitDevInfo.Branch> branches, List<GitDevInfo.Commit> commits,
			List<GitDevInfo.PullRequest> prs, List<GitDevInfo.Build> builds) {

		static DevInfoResponse notConnected() {
			return new DevInfoResponse(false, null, null, null,
					List.of(), List.of(), List.of(), List.of());
		}

		static DevInfoResponse connected(Project.Git git, GitDevInfo info) {
			if (info == null) {
				return new DevInfoResponse(true, git.getProvider(), git.getOwner(), git.getRepo(),
						List.of(), List.of(), List.of(), List.of());
			}
			return new DevInfoResponse(true, git.getProvider(), git.getOwner(), git.getRepo(),
					info.getBranches(), info.getCommits(), info.getPrs(), info.getBuilds());
		}
	}
}
