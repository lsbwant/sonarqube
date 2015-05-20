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

package org.sonar.server.computation.component;

public class Component {

  private Long id;
  private String uuid;
  private String key;

  public Long getId() {
    return id;
  }

  public Component setId(Long id) {
    this.id = id;
    return this;
  }

  public String getKey() {
    return key;
  }

  public Component setKey(String key) {
    this.key = key;
    return this;
  }

  public String getUuid() {
    return uuid;
  }

  public Component setUuid(String uuid) {
    this.uuid = uuid;
    return this;
  }
}
