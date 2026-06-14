package hn.asta.hivora.search;

import hn.asta.hivora.article.Article;
import hn.asta.hivora.board.AgileBoard;
import hn.asta.hivora.board.Sprint;
import hn.asta.hivora.issue.Issue;
import hn.asta.hivora.issue.IssueRepository;
import hn.asta.hivora.project.Project;
import hn.asta.hivora.project.ProjectRepository;
import hn.asta.hivora.search.SearchResponse.SearchGroup;
import hn.asta.hivora.user.User;
import hn.asta.hivora.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.TextCriteria;
import org.springframework.data.mongodb.core.query.TextQuery;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Unified global search across Issues, Projects, People, Boards & Sprints and
 * Knowledge — the backend for the ⌘K palette.
 *
 * <p><b>Hybrid Mongo-native strategy.</b> For each collection we run two cheap,
 * index-backed, bounded queries and merge them:
 * <ul>
 *   <li>a case-insensitive <b>regex</b> over the short label fields — contains
 *       on names/titles, anchored prefix on ids/keys — which gives true
 *       as-you-type and partial-id matching ({@code HIV-23 → HIV-231},
 *       {@code len → Lena}); and</li>
 *   <li>a Mongo <b>$text</b> query over the per-collection text index (created
 *       from {@code @TextIndexed} fields incl. descriptions / article content)
 *       for stemmed, relevance-ranked full-text matches.</li>
 * </ul>
 * Results are deduped by id, prefix matches floated to the top, then capped.
 * No external search engine — fits the self-hosted MongoDB deployment.
 */
@Service
@RequiredArgsConstructor
public class SearchService {

	/** Per-group cap in "all" scope; the design shows up to 5 per group. */
	private static final int CAP_ALL = 5;
	/** Per-group cap when a single category is selected. */
	private static final int CAP_SCOPED = 24;
	/** Over-fetch factor before ranking/dedupe so the cap survives merging. */
	private static final int CANDIDATE_FACTOR = 3;

	private static final String F_TITLE = "title";
	private static final String F_NAME = "name";
	private static final String F_TAGS = "tags";
	private static final String F_UPDATED = "updatedAt";

	private final MongoTemplate mongo;
	private final UserRepository users;
	private final ProjectRepository projects;
	private final IssueRepository issues;

	public SearchResponse search(String rawQuery, String scope) {
		String q = rawQuery == null ? "" : rawQuery.trim();
		SearchCategory only = SearchCategory.parse(scope);
		int cap = only == null ? CAP_ALL : CAP_SCOPED;

		List<SearchGroup> groups = new ArrayList<>();
		if (!q.isBlank()) {
			for (SearchCategory cat : SearchCategory.values()) {
				if (only != null && cat != only) continue;
				List<SearchHit> hits = switch (cat) {
					case ISSUES -> searchIssues(q, cap);
					case PROJECTS -> searchProjects(q, cap);
					case PEOPLE -> searchPeople(q, cap);
					case BOARDS -> searchBoards(q, cap);
					case DOCS -> searchDocs(q, cap);
				};
				if (!hits.isEmpty()) groups.add(new SearchGroup(cat.name(), hits));
			}
		}
		return new SearchResponse(groups, counts());
	}

	// ─────────────────────────── per-category ─────────────────────────────

	private List<SearchHit> searchIssues(String q, int cap) {
		List<Issue> hits = hybrid(Issue.class, q, cap,
				List.of(contains(F_TITLE, q), prefix("readableId", q), contains(F_TAGS, q)),
				F_UPDATED, Issue::getId, Issue::getTitle);

		Map<String, User> byId = userMap(hits.stream()
				.map(Issue::getAssigneeId).filter(Objects::nonNull).collect(Collectors.toSet()));

		return hits.stream().map(it -> {
			User assignee = it.getAssigneeId() == null ? null : byId.get(it.getAssigneeId());
			return SearchHit.builder()
					.category(SearchCategory.ISSUES.name())
					.id(it.getId())
					.route("/issues/" + it.getId())
					.title(it.getTitle())
					.readableId(it.getReadableId())
					.type(it.getType() != null ? it.getType().name() : "TASK")
					.state(it.getState())
					.assigneeName(assignee != null ? assignee.getDisplayName() : null)
					.assigneeAvatarUrl(assignee != null ? assignee.getAvatarUrl() : null)
					.build();
		}).toList();
	}

	private List<SearchHit> searchProjects(String q, int cap) {
		List<Project> hits = hybrid(Project.class, q, cap,
				List.of(contains(F_NAME, q), prefix("key", q)),
				F_UPDATED, Project::getId, Project::getName);

		Map<String, User> byId = userMap(hits.stream()
				.flatMap(p -> p.getMemberIds().stream()).collect(Collectors.toSet()));

		return hits.stream().map(p -> {
			long total = issues.countByProjectId(p.getId());
			long done = p.getResolvedStates().isEmpty()
					? 0
					: issues.countByProjectIdAndStateIn(p.getId(), p.getResolvedStates());
			List<String> memberNames = p.getMemberIds().stream()
					.map(id -> byId.containsKey(id) ? byId.get(id).getDisplayName() : id)
					.toList();
			return SearchHit.builder()
					.category(SearchCategory.PROJECTS.name())
					.id(p.getId())
					.route("/projects")
					.title(p.getName())
					.projectKey(p.getKey())
					.projectColor(p.getColor())
					.openCount((int) Math.max(0, total - done))
					.doneCount((int) done)
					.memberNames(memberNames)
					.build();
		}).toList();
	}

	private List<SearchHit> searchPeople(String q, int cap) {
		List<User> hits = hybrid(User.class, q, cap,
				List.of(contains("displayName", q), contains("username", q), contains(F_TITLE, q)),
				null, User::getId, User::getDisplayName);

		return hits.stream()
				.filter(User::isActive)
				.map(u -> SearchHit.builder()
						.category(SearchCategory.PEOPLE.name())
						.id(u.getId())
						.route("/admin/users")
						.title(u.getDisplayName())
						.subtitle(u.getTitle())
						.avatarUrl(u.getAvatarUrl())
						.build())
				.toList();
	}

	private List<SearchHit> searchBoards(String q, int cap) {
		List<AgileBoard> boards = hybrid(AgileBoard.class, q, cap,
				List.of(contains(F_NAME, q)), null, AgileBoard::getId, AgileBoard::getName);
		List<Sprint> sprints = hybrid(Sprint.class, q, cap,
				List.of(contains(F_NAME, q), contains("goal", q)), null, Sprint::getId, Sprint::getName);

		List<SearchHit> out = new ArrayList<>();
		for (AgileBoard b : boards) {
			out.add(SearchHit.builder()
					.category(SearchCategory.BOARDS.name())
					.id(b.getId())
					.route("/board")
					.title(b.getName())
					.subtitle("Agile board")
					.build());
		}
		for (Sprint s : sprints) {
			out.add(SearchHit.builder()
					.category(SearchCategory.BOARDS.name())
					.id(s.getId())
					.route("/board")
					.title(s.getName())
					.subtitle(s.getGoal() != null && !s.getGoal().isBlank() ? s.getGoal() : "Sprint")
					.build());
		}
		return out.size() > cap ? out.subList(0, cap) : out;
	}

	private List<SearchHit> searchDocs(String q, int cap) {
		List<Article> hits = hybrid(Article.class, q, cap,
				List.of(contains(F_TITLE, q), contains(F_TAGS, q)),
				F_UPDATED, Article::getId, Article::getTitle);

		Map<String, Project> byId = projectMap(hits.stream()
				.map(Article::getProjectId).filter(Objects::nonNull).collect(Collectors.toSet()));

		return hits.stream().map(a -> SearchHit.builder()
				.category(SearchCategory.DOCS.name())
				.id(a.getId())
				.route("/knowledge/" + a.getId())
				.title(a.getTitle())
				.space(a.getProjectId() != null && byId.containsKey(a.getProjectId())
						? byId.get(a.getProjectId()).getName()
						: "Knowledge")
				.updatedAt(a.getUpdatedAt())
				.build()).toList();
	}

	// ─────────────────────────── hybrid core ──────────────────────────────

	/**
	 * Runs the regex + $text pair, merges (regex first, then text), dedupes by
	 * {@code idFn}, floats prefix matches on {@code labelFn} to the top and caps.
	 */
	private <T> List<T> hybrid(Class<T> type, String q, int cap, List<Criteria> regexOrs,
			String sortField, Function<T, String> idFn, Function<T, String> labelFn) {
		int candidates = cap * CANDIDATE_FACTOR;

		Query regexQuery = new Query(new Criteria()
				.orOperator(regexOrs.toArray(Criteria[]::new))).limit(candidates);
		if (sortField != null) regexQuery.with(Sort.by(Sort.Direction.DESC, sortField));
		List<T> regexHits = mongo.find(regexQuery, type);

		List<T> textHits = List.of();
		if (q.length() >= 2) {
			try {
				TextCriteria tc = TextCriteria.forDefaultLanguage().matchingAny(q.split("\\s+"));
				TextQuery textQuery = TextQuery.queryText(tc).sortByScore();
				textQuery.limit(candidates);
				textHits = mongo.find(textQuery, type);
			} catch (RuntimeException ignored) {
				// No usable text index / unsupported term — regex already covers it.
			}
		}

		LinkedHashMap<String, T> merged = new LinkedHashMap<>();
		for (T t : regexHits) merged.putIfAbsent(idFn.apply(t), t);
		for (T t : textHits) merged.putIfAbsent(idFn.apply(t), t);

		String lq = q.toLowerCase();
		return merged.values().stream()
				.sorted(Comparator.<T>comparingInt(t -> {
					String label = labelFn.apply(t);
					return label != null && label.toLowerCase().startsWith(lq) ? 0 : 1;
				}))
				.limit(cap)
				.toList();
	}

	private Map<String, Long> counts() {
		Map<String, Long> counts = new LinkedHashMap<>();
		counts.put(SearchCategory.ISSUES.name(), mongo.estimatedCount(Issue.class));
		counts.put(SearchCategory.PROJECTS.name(), mongo.estimatedCount(Project.class));
		counts.put(SearchCategory.PEOPLE.name(), mongo.estimatedCount(User.class));
		counts.put(SearchCategory.BOARDS.name(),
				mongo.estimatedCount(AgileBoard.class) + mongo.estimatedCount(Sprint.class));
		counts.put(SearchCategory.DOCS.name(), mongo.estimatedCount(Article.class));
		return counts;
	}

	// ─────────────────────────── helpers ──────────────────────────────────

	/** Case-insensitive "contains" — regex-escaped (NoSQL-injection safe). */
	private static Criteria contains(String field, String q) {
		return Criteria.where(field).regex(Pattern.quote(q), "i");
	}

	/** Case-insensitive anchored prefix — index-friendly for ids/keys. */
	private static Criteria prefix(String field, String q) {
		return Criteria.where(field).regex("^" + Pattern.quote(q), "i");
	}

	private Map<String, User> userMap(Collection<String> ids) {
		if (ids.isEmpty()) return Map.of();
		Map<String, User> map = new HashMap<>();
		users.findAllById(ids).forEach(u -> map.put(u.getId(), u));
		return map;
	}

	private Map<String, Project> projectMap(Set<String> ids) {
		if (ids.isEmpty()) return Map.of();
		Map<String, Project> map = new HashMap<>();
		projects.findAllById(ids).forEach(p -> map.put(p.getId(), p));
		return map;
	}
}
