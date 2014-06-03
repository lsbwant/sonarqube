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
package org.sonar.core.log.db;

import com.google.common.base.Preconditions;

import java.io.Serializable;
import java.util.Date;

/**
 * @since 4.4
 */
public class LogKey implements Serializable {

  private Date time;
  private LogDto.Payload payload;
  private String author;

  public LogKey(Date time, LogDto.Payload payload, String author) {
    this.time = time;
    this.payload = payload;
    this.author = author;
  }

  /**
   * Create a key. Parameters are NOT null.
   */
  public static LogKey of(Date time, LogDto.Payload payload, String author) {
    Preconditions.checkArgument(time != null, "Time must be set");
    Preconditions.checkArgument(payload != null, "Payload must be set");
    Preconditions.checkArgument(author != null, "Author must be set");
    return new LogKey(time, payload, author);
  }

  /**
   * Create a key from a string representation (see {@link #toString()}. An {@link IllegalArgumentException} is raised
   * if the format is not valid.
   */
  public static LogKey parse(String s) {
    String[] split = s.split(":");
    Preconditions.checkArgument(split.length == 3, "Invalid log key: " + s);
    return LogKey.of(new Date(Long.getLong(split[0])),
      LogDto.Payload.valueOf(split[1]), split[2]);
  }

  public Date getTime() {
    return time;
  }

  public LogKey setTime(Date time) {
    this.time = time;
    return this;
  }

  public LogDto.Payload getPayload() {
    return payload;
  }

  public LogKey setPayload(LogDto.Payload payload) {
    this.payload = payload;
    return this;
  }

  public String getAuthor() {
    return author;
  }

  public LogKey setAuthor(String author) {
    this.author = author;
    return this;
  }
}
