/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.scache.network.util;

import com.google.common.collect.Maps;

import java.util.Map;
import java.util.NoSuchElementException;

/** ConfigProvider based on a Map (copied in the constructor). */
public class MapConfigProvider extends ConfigProvider {
  private final Map<String, String> config;

  public MapConfigProvider(Map<String, String> config) {
    this.config = Maps.newHashMap(config);
  }

  @Override
  public String get(String name) {
    String value = config.get(name);
    if (value == null) {
      throw new NoSuchElementException(name);
    }
    return value;
  }
}
