/*
 * SonarQube, open source software quality management tool.
 * Copyright (C) 2008-2014 SonarSource
 * mailto:contact AT sonarsource DOT com
 *
 * SonarQube is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * SonarQube is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */

package org.sonar.server.project.ws;

import com.google.common.collect.ImmutableMap;
import org.junit.After;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.junit.rules.ExpectedException;
import org.sonar.api.config.Settings;
import org.sonar.api.resources.Qualifiers;
import org.sonar.api.resources.ResourceType;
import org.sonar.api.resources.ResourceTypes;
import org.sonar.api.rule.RuleKey;
import org.sonar.api.utils.System2;
import org.sonar.api.web.UserRole;
import org.sonar.core.component.ComponentDto;
import org.sonar.core.component.SnapshotDto;
import org.sonar.core.issue.db.IssueDto;
import org.sonar.core.persistence.DbSession;
import org.sonar.core.persistence.DbTester;
import org.sonar.core.purge.PurgeDao;
import org.sonar.core.purge.PurgeProfiler;
import org.sonar.core.resource.ResourceDao;
import org.sonar.core.rule.RuleDto;
import org.sonar.server.component.ComponentCleanerService;
import org.sonar.server.component.ComponentTesting;
import org.sonar.server.component.SnapshotTesting;
import org.sonar.server.component.db.ComponentDao;
import org.sonar.server.component.db.SnapshotDao;
import org.sonar.server.db.DbClient;
import org.sonar.server.es.EsTester;
import org.sonar.server.exceptions.ForbiddenException;
import org.sonar.server.issue.IssueTesting;
import org.sonar.server.issue.db.IssueDao;
import org.sonar.server.issue.index.IssueAuthorizationIndexer;
import org.sonar.server.issue.index.IssueIndexDefinition;
import org.sonar.server.issue.index.IssueIndexer;
import org.sonar.server.rule.RuleTesting;
import org.sonar.server.rule.db.RuleDao;
import org.sonar.server.source.index.SourceLineDoc;
import org.sonar.server.source.index.SourceLineIndexDefinition;
import org.sonar.server.source.index.SourceLineIndexer;
import org.sonar.server.test.index.TestDoc;
import org.sonar.server.test.index.TestIndexDefinition;
import org.sonar.server.test.index.TestIndexer;
import org.sonar.server.tester.UserSessionRule;
import org.sonar.server.ws.WsTester;
import org.sonar.test.DbTests;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Matchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Category(DbTests.class)
public class DeleteActionTest {

  @ClassRule
  public static DbTester db = new DbTester();
  @ClassRule
  public static EsTester es = new EsTester().addDefinitions(new IssueIndexDefinition(new Settings()), new SourceLineIndexDefinition(new Settings()),
    new TestIndexDefinition(new Settings()));
  @Rule
  public UserSessionRule userSessionRule = UserSessionRule.standalone();
  @Rule
  public ExpectedException expectedException = ExpectedException.none();
  WsTester ws;
  DbClient dbClient;
  DbSession dbSession;
  ResourceType resourceType;

  @Before
  public void setUp() throws Exception {
    ComponentDao componentDao = new ComponentDao();
    ResourceDao resourceDao = new ResourceDao(db.myBatis(), System2.INSTANCE);
    PurgeDao purgeDao = new PurgeDao(db.myBatis(), resourceDao, new PurgeProfiler(), System2.INSTANCE);
    dbClient = new DbClient(db.database(), db.myBatis(), componentDao, purgeDao, new RuleDao(System2.INSTANCE), new IssueDao(db.myBatis()), new SnapshotDao(System2.INSTANCE));
    dbSession = dbClient.openSession(false);
    resourceType = mock(ResourceType.class);
    when(resourceType.getBooleanProperty(anyString())).thenReturn(true);
    ResourceTypes mockResourceTypes = mock(ResourceTypes.class);
    when(mockResourceTypes.get(anyString())).thenReturn(resourceType);
    ws = new WsTester(new ProjectsWs(new DeleteAction(new ComponentCleanerService(dbClient, new IssueAuthorizationIndexer(dbClient, es.client()), new IssueIndexer(
      dbClient, es.client()), new SourceLineIndexer(dbClient, es.client()), new TestIndexer(dbClient, es.client()), mockResourceTypes), dbClient, userSessionRule)));
    db.truncateTables();
    es.truncateIndices();
  }

  @After
  public void tearDown() throws Exception {
    dbSession.close();
  }

  @Test
  public void delete_projects_and_data_in_db_by_uuids() throws Exception {
    userSessionRule.setGlobalPermissions(UserRole.ADMIN);
    long snapshotId1 = insertNewProjectInDbAndReturnSnapshotId(1);
    long snapshotId2 = insertNewProjectInDbAndReturnSnapshotId(2);
    long snapshotId3 = insertNewProjectInDbAndReturnSnapshotId(3);
    long snapshotId4 = insertNewProjectInDbAndReturnSnapshotId(4);

    ws.newGetRequest("api/projects", "delete")
      .setParam("uuids", "project-uuid-1, project-uuid-3, project-uuid-4").execute();
    dbSession.commit();

    assertThat(dbClient.componentDao().selectByUuids(dbSession, Arrays.asList("project-uuid-1", "project-uuid-3", "project-uuid-4"))).isEmpty();
    assertThat(dbClient.componentDao().selectByUuid(dbSession, "project-uuid-2")).isNotNull();
    assertThat(dbClient.snapshotDao().getNullableByKey(dbSession, snapshotId1)).isNull();
    assertThat(dbClient.snapshotDao().getNullableByKey(dbSession, snapshotId3)).isNull();
    assertThat(dbClient.snapshotDao().getNullableByKey(dbSession, snapshotId4)).isNull();
    assertThat(dbClient.snapshotDao().getNullableByKey(dbSession, snapshotId2)).isNotNull();
    assertThat(dbClient.issueDao().selectByKeys(dbSession, Arrays.asList("issue-key-1", "issue-key-3", "issue-key-4"))).isEmpty();
    assertThat(dbClient.issueDao().selectByKey(dbSession, "issue-key-2")).isNotNull();
  }

  @Test
  public void delete_projects_and_data_in_db_by_keys() throws Exception {
    userSessionRule.setGlobalPermissions(UserRole.ADMIN);
    insertNewProjectInDbAndReturnSnapshotId(1);
    insertNewProjectInDbAndReturnSnapshotId(2);
    insertNewProjectInDbAndReturnSnapshotId(3);
    insertNewProjectInDbAndReturnSnapshotId(4);

    ws.newGetRequest("api/projects", "delete")
      .setParam("keys", "project-key-1, project-key-3, project-key-4").execute();
    dbSession.commit();

    assertThat(dbClient.componentDao().selectByUuids(dbSession, Arrays.asList("project-uuid-1", "project-uuid-3", "project-uuid-4"))).isEmpty();
    assertThat(dbClient.componentDao().selectByUuid(dbSession, "project-uuid-2")).isNotNull();
  }

  @Test
  public void delete_documents_indexes() throws Exception {
    userSessionRule.setGlobalPermissions(UserRole.ADMIN);
    insertNewProjectInIndexes(1);
    insertNewProjectInIndexes(2);
    insertNewProjectInIndexes(3);
    insertNewProjectInIndexes(4);

    ws.newGetRequest("api/projects", "delete")
      .setParam("keys", "project-key-1, project-key-3, project-key-4").execute();

    String remainingProjectUuid = "project-uuid-2";
    assertThat(es.getDocumentFieldValues(IssueIndexDefinition.INDEX, IssueIndexDefinition.TYPE_ISSUE, IssueIndexDefinition.FIELD_ISSUE_PROJECT_UUID))
      .containsOnly(remainingProjectUuid);
    assertThat(es.getDocumentFieldValues(IssueIndexDefinition.INDEX, IssueIndexDefinition.TYPE_AUTHORIZATION, IssueIndexDefinition.FIELD_AUTHORIZATION_PROJECT_UUID))
      .containsOnly(remainingProjectUuid);
    assertThat(es.getDocumentFieldValues(SourceLineIndexDefinition.INDEX, SourceLineIndexDefinition.TYPE, SourceLineIndexDefinition.FIELD_PROJECT_UUID))
      .containsOnly(remainingProjectUuid);
    assertThat(es.getDocumentFieldValues(TestIndexDefinition.INDEX, TestIndexDefinition.TYPE, TestIndexDefinition.FIELD_PROJECT_UUID))
      .containsOnly(remainingProjectUuid);
  }

  @Test
  public void web_service_returns_204() throws Exception {
    userSessionRule.setGlobalPermissions(UserRole.ADMIN);
    insertNewProjectInDbAndReturnSnapshotId(1);

    WsTester.Result result = ws.newGetRequest("api/projects", "delete").setParam("uuids", "project-uuid-1").execute();

    result.assertNoContent();
  }

  @Test
  public void fail_if_insufficient_privileges() throws Exception {
    userSessionRule.setGlobalPermissions(UserRole.CODEVIEWER, UserRole.ISSUE_ADMIN, UserRole.USER);
    expectedException.expect(ForbiddenException.class);

    ws.newGetRequest("api/projects", "delete").setParam("uuids", "whatever-the-uuid").execute();
  }

  @Test
  public void fail_if_scope_is_not_project() throws Exception {
    userSessionRule.setGlobalPermissions(UserRole.ADMIN);
    expectedException.expect(IllegalArgumentException.class);
    dbClient.componentDao().insert(dbSession, ComponentTesting.newFileDto(ComponentTesting.newProjectDto(), "file-uuid"));
    dbSession.commit();

    ws.newGetRequest("api/projects", "delete").setParam("uuids", "file-uuid").execute();
  }

  @Test
  public void fail_if_qualifier_is_not_deletable() throws Exception {
    userSessionRule.setGlobalPermissions(UserRole.ADMIN);
    expectedException.expect(IllegalArgumentException.class);
    dbClient.componentDao().insert(dbSession, ComponentTesting.newProjectDto("project-uuid").setQualifier(Qualifiers.FILE));
    dbSession.commit();
    when(resourceType.getBooleanProperty(anyString())).thenReturn(false);

    ws.newGetRequest("api/projects", "delete").setParam("uuids", "project-uuid").execute();
  }

  private long insertNewProjectInDbAndReturnSnapshotId(int id) {
    String suffix = String.valueOf(id);
    ComponentDto project = ComponentTesting
      .newProjectDto("project-uuid-" + suffix)
      .setKey("project-key-" + suffix);
    RuleDto rule = RuleTesting.newDto(RuleKey.of("sonarqube", "rule-" + suffix));
    dbClient.ruleDao().insert(dbSession, rule);
    IssueDto issue = IssueTesting.newDto(rule, project, project).setKee("issue-key-" + suffix);
    dbClient.componentDao().insert(dbSession, project);
    SnapshotDto snapshot = dbClient.snapshotDao().insert(dbSession, SnapshotTesting.createForProject(project));
    dbClient.issueDao().insert(dbSession, issue);
    dbSession.commit();

    return snapshot.getId();
  }

  private void insertNewProjectInIndexes(int id) throws Exception {
    String suffix = String.valueOf(id);
    ComponentDto project = ComponentTesting
      .newProjectDto("project-uuid-" + suffix)
      .setKey("project-key-" + suffix);
    dbClient.componentDao().insert(dbSession, project);
    dbSession.commit();

    es.putDocuments(IssueIndexDefinition.INDEX, IssueIndexDefinition.TYPE_ISSUE, IssueTesting.newDoc("issue-key-" + suffix, project));
    SourceLineDoc sourceLineDoc = new SourceLineDoc()
      .setProjectUuid(project.uuid())
      .setFileUuid(project.uuid());
    es.putDocuments(IssueIndexDefinition.INDEX, IssueIndexDefinition.TYPE_AUTHORIZATION,
      ImmutableMap.<String, Object>of(IssueIndexDefinition.FIELD_AUTHORIZATION_PROJECT_UUID, project.uuid()));

    es.putDocuments(SourceLineIndexDefinition.INDEX, SourceLineIndexDefinition.TYPE, sourceLineDoc);
    TestDoc testDoc = new TestDoc().setUuid("test-uuid-" + suffix).setProjectUuid(project.uuid()).setFileUuid(project.uuid());
    es.putDocuments(TestIndexDefinition.INDEX, TestIndexDefinition.TYPE, testDoc);
  }
}
