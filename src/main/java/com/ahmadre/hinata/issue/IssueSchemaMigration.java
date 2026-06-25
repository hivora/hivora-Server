package com.ahmadre.hinata.issue;

import com.mongodb.client.MongoCollection;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * One-time, idempotent backfill of the multi-assignee schema: older issue
 * documents stored only the single {@code assigneeId}. This seeds the new
 * {@code assigneeIds} array from it so membership queries ("assigned to me",
 * including secondary assignees) and the multi-assignee picker work uniformly.
 * Runs against raw BSON before any typed read; no-op once converted / on fresh DBs.
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@RequiredArgsConstructor
public class IssueSchemaMigration implements ApplicationRunner {

	private final MongoTemplate mongo;

	@Override
	public void run(ApplicationArguments args) {
		MongoCollection<Document> col = mongo.getCollection("issues");
		int migrated = 0;
		for (Document doc : col.find()) {
			Object existing = doc.get("assigneeIds");
			boolean hasList = existing instanceof List<?> list && !list.isEmpty();
			if (hasList) continue;
			Object assignee = doc.get("assigneeId");
			List<String> ids = (assignee instanceof String s && !s.isBlank())
					? List.of(s) : List.of();
			col.updateOne(new Document("_id", doc.get("_id")),
					new Document("$set", new Document("assigneeIds", ids)));
			migrated++;
		}
		if (migrated > 0) {
			log.info("IssueSchemaMigration: backfilled assigneeIds on {} issue document(s)", migrated);
		}
	}
}
