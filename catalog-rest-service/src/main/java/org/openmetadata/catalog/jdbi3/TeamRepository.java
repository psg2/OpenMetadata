/*
 *  Copyright 2021 Collate
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *  http://www.apache.org/licenses/LICENSE-2.0
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.openmetadata.catalog.jdbi3;

import static org.openmetadata.catalog.Entity.FIELD_OWNER;
import static org.openmetadata.catalog.Entity.TEAM;
import static org.openmetadata.catalog.api.teams.CreateTeam.TeamType.BUSINESS_UNIT;
import static org.openmetadata.catalog.api.teams.CreateTeam.TeamType.DEPARTMENT;
import static org.openmetadata.catalog.api.teams.CreateTeam.TeamType.DIVISION;
import static org.openmetadata.catalog.api.teams.CreateTeam.TeamType.ORGANIZATION;
import static org.openmetadata.common.utils.CommonUtil.listOrEmpty;

import com.fasterxml.jackson.core.JsonProcessingException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.openmetadata.catalog.Entity;
import org.openmetadata.catalog.api.teams.CreateTeam.TeamType;
import org.openmetadata.catalog.entity.teams.Team;
import org.openmetadata.catalog.jdbi3.CollectionDAO.EntityRelationshipRecord;
import org.openmetadata.catalog.resources.teams.TeamResource;
import org.openmetadata.catalog.type.EntityReference;
import org.openmetadata.catalog.type.Include;
import org.openmetadata.catalog.type.Relationship;
import org.openmetadata.catalog.util.EntityUtil;
import org.openmetadata.catalog.util.EntityUtil.Fields;
import org.openmetadata.catalog.util.JsonUtils;

@Slf4j
public class TeamRepository extends EntityRepository<Team> {
  static final String TEAM_UPDATE_FIELDS = "owner,profile,users,defaultRoles";
  static final String TEAM_PATCH_FIELDS = "owner,profile,users,defaultRoles";
  Team organization = null;

  public TeamRepository(CollectionDAO dao) {
    super(TeamResource.COLLECTION_PATH, TEAM, Team.class, dao.teamDAO(), dao, TEAM_PATCH_FIELDS, TEAM_UPDATE_FIELDS);
  }

  @Override
  public Team setFields(Team team, Fields fields) throws IOException {
    if (!fields.contains("profile")) {
      team.setProfile(null); // Clear the profile attribute, if it was not requested
    }
    team.setUsers(fields.contains("users") ? getUsers(team) : null);
    team.setOwns(fields.contains("owns") ? getOwns(team) : null);
    team.setDefaultRoles(fields.contains("defaultRoles") ? getDefaultRoles(team) : null);
    team.setOwner(fields.contains(FIELD_OWNER) ? getOwner(team) : null);
    team.setParents(fields.contains("parents") ? getParents(team) : null);
    team.setChildren(fields.contains("children") ? getChildren(team) : null);
    return team;
  }

  @Override
  public void restorePatchAttributes(Team original, Team updated) {
    // Patch can't make changes to following fields. Ignore the changes
    updated.withName(original.getName()).withId(original.getId());
  }

  @Override
  public void prepare(Team team) throws IOException {
    setFullyQualifiedName(team);
    populateOwner(team.getOwner()); // Validate owner
    populateParents(team); // Validate parents
    populateChildren(team); // Validate children
    validateUsers(team.getUsers());
    validateRoles(team.getDefaultRoles());
  }

  @Override
  public void storeEntity(Team team, boolean update) throws IOException {
    // Relationships and fields such as href are derived and not stored as part of json
    EntityReference owner = team.getOwner();
    List<EntityReference> users = team.getUsers();
    List<EntityReference> defaultRoles = team.getDefaultRoles();
    List<EntityReference> parents = team.getParents();
    List<EntityReference> children = team.getChildren();

    // Don't store users, defaultRoles, href as JSON. Build it on the fly based on relationships
    team.withUsers(null).withDefaultRoles(null).withHref(null).withOwner(null);

    store(team.getId(), team, update);

    // Restore the relationships
    team.withUsers(users).withDefaultRoles(defaultRoles).withOwner(owner).withParents(parents).withChildren(children);
  }

  @Override
  public void storeRelationships(Team team) {
    // Add team owner relationship
    storeOwner(team, team.getOwner());
    for (EntityReference user : listOrEmpty(team.getUsers())) {
      addRelationship(team.getId(), user.getId(), TEAM, Entity.USER, Relationship.HAS);
    }
    for (EntityReference defaultRole : listOrEmpty(team.getDefaultRoles())) {
      addRelationship(team.getId(), defaultRole.getId(), TEAM, Entity.ROLE, Relationship.HAS);
    }
    for (EntityReference parent : listOrEmpty(team.getParents())) {
      addRelationship(parent.getId(), team.getId(), TEAM, TEAM, Relationship.PARENT_OF);
    }
    for (EntityReference child : listOrEmpty(team.getChildren())) {
      addRelationship(team.getId(), child.getId(), TEAM, TEAM, Relationship.PARENT_OF);
    }
  }

  @Override
  public TeamUpdater getUpdater(Team original, Team updated, Operation operation) {
    return new TeamUpdater(original, updated, operation);
  }

  private List<EntityReference> getUsers(Team team) throws IOException {
    List<EntityRelationshipRecord> userIds = findTo(team.getId(), TEAM, Relationship.HAS, Entity.USER);
    return EntityUtil.populateEntityReferences(userIds, Entity.USER);
  }

  private List<EntityReference> getOwns(Team team) throws IOException {
    // Compile entities owned by the team
    return EntityUtil.getEntityReferences(
        daoCollection.relationshipDAO().findTo(team.getId().toString(), TEAM, Relationship.OWNS.ordinal()));
  }

  private List<EntityReference> getDefaultRoles(Team team) throws IOException {
    List<EntityRelationshipRecord> defaultRoleIds = findTo(team.getId(), TEAM, Relationship.HAS, Entity.ROLE);
    return EntityUtil.populateEntityReferences(defaultRoleIds, Entity.ROLE);
  }

  private List<EntityReference> getParents(Team team) throws IOException {
    List<EntityRelationshipRecord> parents = findFrom(team.getId(), TEAM, Relationship.PARENT_OF, TEAM);
    return EntityUtil.populateEntityReferences(parents, TEAM);
  }

  private List<EntityReference> getChildren(Team team) throws IOException {
    List<EntityRelationshipRecord> children = findTo(team.getId(), TEAM, Relationship.PARENT_OF, TEAM);
    return EntityUtil.populateEntityReferences(children, TEAM);
  }

  private void populateChildren(Team team) throws IOException {
    List<EntityReference> childrenRefs = team.getChildren();
    if (childrenRefs == null) {
      return;
    }
    List<Team> children = getTeams(childrenRefs);
    switch (team.getTeamType()) {
      case DEPARTMENT:
        validateTeams(team, "child", children, DEPARTMENT);
        break;
      case DIVISION:
        validateTeams(team, "child", children, DEPARTMENT, DIVISION);
        break;
      case BUSINESS_UNIT:
        validateTeams(team, "child", children, BUSINESS_UNIT, DIVISION);
        break;
      case ORGANIZATION:
        validateTeams(team, "child", children, BUSINESS_UNIT, DIVISION, DEPARTMENT);
        break;
    }
    populateTeamRefs(childrenRefs, children);
  }

  private void populateParents(Team team) throws IOException {
    List<EntityReference> parentRefs = team.getParents();
    // By default, all the teams created without parents has the top Organization as the parent
    System.out.println("XXX parenRefs " + team.getParents());
    if (parentRefs == null) {
      System.out.println("XXX defaulting to org parent");
      team.setParents(List.of(organization.getEntityReference()));
      return;
    }
    List<Team> parents = getTeams(parentRefs);
    switch (team.getTeamType()) {
      case DEPARTMENT:
        validateTeams(team, "parent", parents, DEPARTMENT, DIVISION, BUSINESS_UNIT, ORGANIZATION);
        break;
      case DIVISION:
        validateSingleParent(parentRefs);
        validateTeams(team, "parent", parents, DIVISION, BUSINESS_UNIT, ORGANIZATION);
        break;
      case BUSINESS_UNIT:
        validateSingleParent(parentRefs);
        validateTeams(team, "parent", parents, BUSINESS_UNIT, ORGANIZATION);
        break;
      case ORGANIZATION:
        throw new IllegalArgumentException("Team of type Organization can't have a parent team");
    }
    populateTeamRefs(parentRefs, parents);
  }

  // Populate team refs from team entity list
  private void populateTeamRefs(List<EntityReference> teamRefs, List<Team> teams) {
    for (int i = 0; i < teams.size(); i++) {
      EntityUtil.copy(teamRefs.get(i), teams.get(i).getEntityReference());
    }
  }

  private List<Team> getTeams(List<EntityReference> teamRefs) throws IOException {
    List<Team> teams = new ArrayList<>();
    for (EntityReference teamRef : teamRefs) {
      teams.add(dao.findEntityById(teamRef.getId()));
    }
    return teams;
  }

  // Validate given list of teams are of team types in allowedTeamTypes list
  private void validateTeams(Team team, String relation, List<Team> relatedTeams, TeamType... allowedTeamTypes) {
    List<TeamType> allowed = Arrays.asList(allowedTeamTypes);
    for (Team relatedTeam : relatedTeams) {
      if (!allowed.contains(relatedTeam.getTeamType())) {
        throw new IllegalArgumentException(
            String.format(
                "For table of type %s invalid %s team %s of type %s",
                team.getTeamType(), relation, relatedTeam.getName(), relatedTeam.getTeamType()));
      }
    }
  }

  private void validateSingleParent(List<EntityReference> parentRefs) {
    if (parentRefs.size() != 1) {
      throw new IllegalArgumentException(String.format("Team can have only %s parents", 1));
    }
  }

  public void initOrganization(String orgName) throws IOException {
    String json = dao.findJsonByFqn("Organization", Include.ALL);
    if (json == null) {
      LOG.info("Organization {} is not initialized", orgName);
      organization =
          new Team()
              .withId(UUID.randomUUID())
              .withName(orgName)
              .withDisplayName(orgName)
              .withDescription("Organization under which all the other team hierarchy is created")
              .withTeamType(ORGANIZATION)
              .withUpdatedBy("admin")
              .withUpdatedAt(System.currentTimeMillis());
      // Teams
      try {
        create(null, organization);
      } catch (Exception e) {
        LOG.info("Failed to initialize organization", e);
        throw e;
      }
    } else {
      organization = JsonUtils.readValue(json, Team.class);
      LOG.info("Organization is already initialized");
    }
  }

  /** Handles entity updated from PUT and POST operation. */
  public class TeamUpdater extends EntityUpdater {
    public TeamUpdater(Team original, Team updated, Operation operation) {
      super(original, updated, operation);
    }

    @Override
    public void entitySpecificUpdate() throws IOException {
      recordChange("profile", original.getProfile(), updated.getProfile());
      recordChange("isJoinable", original.getIsJoinable(), updated.getIsJoinable());
      updateUsers(original, updated);
      updateDefaultRoles(original, updated);
    }

    private void updateUsers(Team origTeam, Team updatedTeam) throws JsonProcessingException {
      List<EntityReference> origUsers = listOrEmpty(origTeam.getUsers());
      List<EntityReference> updatedUsers = listOrEmpty(updatedTeam.getUsers());
      updateToRelationships(
          "users", TEAM, origTeam.getId(), Relationship.HAS, Entity.USER, origUsers, updatedUsers, false);
    }

    private void updateDefaultRoles(Team origTeam, Team updatedTeam) throws JsonProcessingException {
      List<EntityReference> origDefaultRoles = listOrEmpty(origTeam.getDefaultRoles());
      List<EntityReference> updatedDefaultRoles = listOrEmpty(updatedTeam.getDefaultRoles());
      updateToRelationships(
          "defaultRoles",
          TEAM,
          origTeam.getId(),
          Relationship.HAS,
          Entity.ROLE,
          origDefaultRoles,
          updatedDefaultRoles,
          false);
    }
  }
}
