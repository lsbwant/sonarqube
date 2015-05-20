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

package org.sonar.server.computation.step;

import com.google.common.collect.Maps;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang.ObjectUtils;
import org.apache.commons.lang.StringUtils;
import org.sonar.api.resources.Qualifiers;
import org.sonar.api.resources.Scopes;
import org.sonar.api.utils.internal.Uuids;
import org.sonar.batch.protocol.Constants;
import org.sonar.batch.protocol.output.BatchReport;
import org.sonar.batch.protocol.output.BatchReportReader;
import org.sonar.core.component.ComponentDto;
import org.sonar.core.component.ComponentKeys;
import org.sonar.core.persistence.DbSession;
import org.sonar.core.util.NonNullInputFunction;
import org.sonar.server.computation.ComputationContext;
import org.sonar.server.db.DbClient;

import javax.annotation.Nullable;

import java.util.List;
import java.util.Map;

public class PersistComponentsStep implements ComputationStep {

  private final DbClient dbClient;

  public PersistComponentsStep(DbClient dbClient) {
    this.dbClient = dbClient;
  }

  @Override
  public String[] supportedProjectQualifiers() {
    return new String[] {Qualifiers.PROJECT};
  }

  @Override
  public void execute(ComputationContext context) {
    DbSession session = dbClient.openSession(false);
    try {
      List<ComponentDto> components = dbClient.componentDao().selectComponentsFromProjectUuid(session, context.getProject().uuid());
      Map<String, ComponentDto> componentDtosByKey = componentDtosByKey(components);
      int rootComponentRef = context.getReportMetadata().getRootComponentRef();
      ComponentContext componentContext = new ComponentContext(context, session, componentDtosByKey);
      recursivelyProcessComponent(componentContext, rootComponentRef, null);
      session.commit();
    } finally {
      session.close();
    }
  }

  private void recursivelyProcessComponent(ComponentContext componentContext, int componentRef, @Nullable ComponentDto moduleParent) {
    BatchReportReader reportReader = componentContext.context.getReportReader();
    BatchReport.Component reportComponent = reportReader.readComponent(componentRef);
    ComponentDto componentDto = processComponent(componentContext, reportComponent, moduleParent);

    for (Integer childRef : reportComponent.getChildRefList()) {
      // If current component is not a module or a project, we need to keep the parent reference to the nearest module
      ComponentDto nextModuleParent = !reportComponent.getType().equals(Constants.ComponentType.PROJECT) && !reportComponent.getType().equals(Constants.ComponentType.MODULE) ?
        moduleParent : componentDto;
      recursivelyProcessComponent(componentContext, childRef, nextModuleParent);
    }
  }

  private ComponentDto processComponent(ComponentContext componentContext, BatchReport.Component reportComponent, @Nullable ComponentDto moduleParent) {
    String path = reportComponent.hasPath() ? reportComponent.getPath() : null;
    String branch = componentContext.context.getReportMetadata().hasBranch() ? componentContext.context.getReportMetadata().getBranch() : null;
    String componentKey = reportComponent.hasKey() || moduleParent == null ?
      ComponentKeys.createKey(reportComponent.getKey(), branch) :
      ComponentKeys.createEffectiveKey(moduleParent.getKey(), path);
    ComponentDto existingComponent = componentContext.componentDtosByKey.get(componentKey);
    if (existingComponent == null) {
      ComponentDto component = createComponent(reportComponent, componentKey, Uuids.create(), moduleParent);
      dbClient.componentDao().insert(componentContext.dbSession, component);
      return component;
    } else {
      ComponentDto component = createComponent(reportComponent, componentKey, existingComponent.uuid(), moduleParent);
      if (updateComponent(existingComponent, component)) {
        dbClient.componentDao().update(componentContext.dbSession, existingComponent);
      }
      return existingComponent;
    }
  }

  private ComponentDto createComponent(BatchReport.Component reportComponent, String componentKey, String uuid, @Nullable ComponentDto parentModule) {
    ComponentDto component = new ComponentDto();
    component.setUuid(uuid);
    component.setKey(componentKey);
    component.setDeprecatedKey(componentKey);
    component.setEnabled(true);
    component.setScope(getScope(reportComponent));
    component.setQualifier(getQualifier(reportComponent));
    component.setName(getFileName(reportComponent));

    if (isProjectOrModule(reportComponent)) {
      component.setLongName(component.name());
      if (reportComponent.hasDescription()) {
        component.setDescription(reportComponent.getDescription());
      }
    } else {
      component.setLongName(reportComponent.getPath());
      if (reportComponent.hasPath()) {
        component.setPath(reportComponent.getPath());
      }
      if (reportComponent.hasLanguage()) {
        component.setLanguage(reportComponent.getLanguage());
      }
    }
    if (parentModule != null) {
      component.setParentProjectId(parentModule.getId());
      component.setProjectUuid(parentModule.projectUuid());
      component.setModuleUuid(parentModule.uuid());
      component.setModuleUuidPath(reportComponent.getType().equals(Constants.ComponentType.MODULE) ?
        parentModule.moduleUuidPath() + component.uuid() + ComponentDto.MODULE_UUID_PATH_SEP :
        parentModule.moduleUuidPath());
    } else {
      component.setProjectUuid(uuid);
      component.setModuleUuidPath(ComponentDto.MODULE_UUID_PATH_SEP + component.uuid() + ComponentDto.MODULE_UUID_PATH_SEP);
    }
    return component;
  }

  private boolean updateComponent(ComponentDto existingComponent, ComponentDto newComponent) {
    boolean isUpdated = false;
    if (Scopes.PROJECT.equals(existingComponent.scope())) {
      if (!newComponent.name().equals(existingComponent.name())) {
        existingComponent.setName(newComponent.name());
        isUpdated = true;
      }
      if (!StringUtils.equals(existingComponent.description(), newComponent.description())) {
        existingComponent.setDescription(newComponent.description());
        isUpdated = true;
      }
    }

    if (!StringUtils.equals(existingComponent.moduleUuid(), newComponent.moduleUuid())) {
      existingComponent.setModuleUuid(newComponent.moduleUuid());
      isUpdated = true;
    }
    if (!existingComponent.moduleUuidPath().equals(newComponent.moduleUuidPath())) {
      existingComponent.setModuleUuidPath(newComponent.moduleUuidPath());
      isUpdated = true;
    }
    if (!ObjectUtils.equals(existingComponent.parentProjectId(), newComponent.parentProjectId())) {
      existingComponent.setParentProjectId(newComponent.parentProjectId());
      isUpdated = true;
    }

    return isUpdated;
  }

  private static boolean isProjectOrModule(BatchReport.Component reportComponent) {
    return reportComponent.getType().equals(Constants.ComponentType.PROJECT) || reportComponent.getType().equals(Constants.ComponentType.MODULE);
  }

  private static String getScope(BatchReport.Component reportComponent) {
    switch (reportComponent.getType()) {
      case PROJECT:
        return Scopes.PROJECT;
      case MODULE:
        return Scopes.PROJECT;
      case DIRECTORY:
        return Scopes.DIRECTORY;
      case FILE:
        return Scopes.FILE;
    }
    throw new IllegalArgumentException(String.format("Unknown type '%s'", reportComponent.getType()));
  }

  private static String getQualifier(BatchReport.Component reportComponent) {
    switch (reportComponent.getType()) {
      case PROJECT:
        return Qualifiers.PROJECT;
      case MODULE:
        return Qualifiers.MODULE;
      case DIRECTORY:
        return Qualifiers.DIRECTORY;
      case FILE:
        return !reportComponent.getIsTest() ? Qualifiers.FILE : Qualifiers.UNIT_TEST_FILE;
    }
    throw new IllegalArgumentException(String.format("Unknown type '%s'", reportComponent.getType()));
  }

  private static String getFileName(BatchReport.Component reportComponent) {
    String path = reportComponent.getPath();
    if (reportComponent.getType() == Constants.ComponentType.PROJECT || reportComponent.getType() == Constants.ComponentType.MODULE) {
      return reportComponent.getName();
    } else if (reportComponent.getType().equals(Constants.ComponentType.DIRECTORY)) {
      return path;
    } else {
      return FilenameUtils.getName(path);
    }
  }

  private Map<String, ComponentDto> componentDtosByKey(List<ComponentDto> components) {
    return Maps.uniqueIndex(components, new NonNullInputFunction<ComponentDto, String>() {
      @Override
      public String doApply(ComponentDto input) {
        return input.key();
      }
    });
  }

  private static class ComponentContext {
    private final ComputationContext context;
    private final Map<String, ComponentDto> componentDtosByKey;
    private final DbSession dbSession;

    public ComponentContext(ComputationContext context, DbSession dbSession, Map<String, ComponentDto> componentDtosByKey) {
      this.componentDtosByKey = componentDtosByKey;
      this.context = context;
      this.dbSession = dbSession;
    }
  }

  @Override
  public String getDescription() {
    return "Feed components cache";
  }
}
