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
package org.sonar.server.search;

import org.elasticsearch.action.ActionRequest;
import org.elasticsearch.action.bulk.BulkRequestBuilder;
import org.elasticsearch.action.bulk.BulkResponse;
import org.elasticsearch.action.delete.DeleteRequest;
import org.elasticsearch.action.update.UpdateRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sonar.api.ServerComponent;
import org.sonar.api.config.Settings;
import org.sonar.api.platform.ComponentContainer;
import org.sonar.core.cluster.WorkQueue;
import org.sonar.core.profiling.Profiling;
import org.sonar.server.search.action.IndexActionRequest;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;

public class IndexQueue extends LinkedBlockingQueue<Runnable>
  implements ServerComponent, WorkQueue<IndexActionRequest> {

  protected final Profiling profiling;

  private final SearchClient searchClient;
  private final ComponentContainer container;

  private static final Logger LOGGER = LoggerFactory.getLogger(IndexQueue.class);

  private static final Integer DEFAULT_QUEUE_SIZE = 200;
  private static final int TIMEOUT = 30000;

  public IndexQueue(Settings settings, SearchClient searchClient, ComponentContainer container) {
    super(DEFAULT_QUEUE_SIZE);
    this.searchClient = searchClient;
    this.container = container;
    this.profiling = new Profiling(settings);
  }

  @Override
  public void enqueue(List<IndexActionRequest> actions) {

    if (actions.isEmpty()) {
      return;
    }
    try {

      BulkRequestBuilder bulkRequestBuilder = new BulkRequestBuilder(searchClient);
      Map<String,Index> indexes = getIndexMap();
      for (IndexActionRequest action : actions) {
        action.setIndex(indexes.get(action.getIndexType()));
      }

      ExecutorService executorService = Executors.newFixedThreadPool(4);

      //invokeAll() blocks until ALL tasks submitted to executor complete
      for (Future<List<ActionRequest>> updateRequests : executorService.invokeAll(actions)) {
        for (ActionRequest update : updateRequests.get()) {
          if (UpdateRequest.class.isAssignableFrom(update.getClass())) {
            bulkRequestBuilder.add((UpdateRequest)update);
          } else if (DeleteRequest.class.isAssignableFrom(update.getClass())) {
            bulkRequestBuilder.add((DeleteRequest)update);
          } else {
            throw new IllegalStateException("Un-managed request type: " + update.getClass());
          }
        }
      }
      executorService.shutdown();

      LOGGER.info("Executing batch request of size: " + bulkRequestBuilder.numberOfActions());

      //execute the request
      BulkResponse response = searchClient.execute(bulkRequestBuilder.setRefresh(true));
    } catch (Exception e) {
      e.printStackTrace();
    }
  }

  private Map<String, Index> getIndexMap() {
    Map<String, Index> indexes = new HashMap<String, Index>();
    for (Index index : container.getComponentsByType(Index.class)) {
      indexes.put(index.getIndexType(), index);
    }
    return indexes;
  }


//
//    int bcount = 0;
//    int ecount = 0;
//    List<String> refreshes = Lists.newArrayList();
//    Set<String> types = Sets.newHashSet();
//    long all_start = System.currentTimeMillis();
//    long indexTime;
//    long refreshTime;
//    long embeddedTime;
//
//    if (actions.size() == 1) {
//      /* Atomic update here */
//      CountDownLatch latch = new CountDownLatch(1);
//      IndexAction action = actions.get(0);
//      action.setLatch(latch);
//      try {
//        indexTime = System.currentTimeMillis();
//        this.offer(action, TIMEOUT, TimeUnit.MILLISECONDS);
//        if (!latch.await(TIMEOUT, TimeUnit.MILLISECONDS)) {
//          throw new IllegalStateException("ES update could not be completed within: " + TIMEOUT + "ms");
//        }
//        bcount++;
//        indexTime = System.currentTimeMillis() - indexTime;
//        // refresh the index.
//        Index<?, ?, ?> index = action.getIndex();
//        if (index != null) {
//          refreshTime = System.currentTimeMillis();
//          index.refresh();
//          refreshTime = System.currentTimeMillis() - refreshTime;
//          refreshes.add(index.getIndexName());
//        }
//        types.add(action.getPayloadClass().getSimpleName());
//      } catch (InterruptedException e) {
//        throw new IllegalStateException("ES update has been interrupted", e);
//      }
//    } else if (actions.size() > 1) {
//      StopWatch basicProfile = profiling.start("search", Profiling.Level.BASIC);
//
//      /* Purge actions that would be overridden  */
//      Long purgeStart = System.currentTimeMillis();
//      List<IndexAction> itemActions = Lists.newArrayList();
//      List<IndexAction> embeddedActions = Lists.newArrayList();
//
//      for (IndexAction action : actions) {
//        if (action.getClass().isAssignableFrom(EmbeddedIndexAction.class)) {
//          embeddedActions.add(action);
//        } else {
//          itemActions.add(action);
//        }
//      }
//
//      LOGGER.debug("INDEX - compressed {} items into {} in {}ms,",
//        actions.size(), itemActions.size() + embeddedActions.size(), System.currentTimeMillis() - purgeStart);
//
//      try {
//        /* execute all item actions */
//        CountDownLatch itemLatch = new CountDownLatch(itemActions.size());
//        indexTime = System.currentTimeMillis();
//        for (IndexAction action : itemActions) {
//          action.setLatch(itemLatch);
//          this.offer(action, TIMEOUT, TimeUnit.MILLISECONDS);
//          types.add(action.getPayloadClass().getSimpleName());
//          bcount++;
//
//        }
//        if (!itemLatch.await(TIMEOUT, TimeUnit.MILLISECONDS)) {
//          throw new IllegalStateException("ES update could not be completed within: " + TIMEOUT + "ms");
//        }
//        indexTime = System.currentTimeMillis() - indexTime;
//
//        /* and now push the embedded */
//        CountDownLatch embeddedLatch = new CountDownLatch(embeddedActions.size());
//        embeddedTime = System.currentTimeMillis();
//        for (IndexAction action : embeddedActions) {
//          action.setLatch(embeddedLatch);
//          this.offer(action, TIMEOUT, TimeUnit.SECONDS);
//          types.add(action.getPayloadClass().getSimpleName());
//          ecount++;
//        }
//        if (!embeddedLatch.await(TIMEOUT, TimeUnit.MILLISECONDS)) {
//          throw new IllegalStateException("ES embedded update could not be completed within: " + TIMEOUT + "ms");
//        }
//        embeddedTime = System.currentTimeMillis() - embeddedTime;
//
//        /* Finally refresh affected indexes */
//        Set<String> refreshedIndexes = new HashSet<String>();
//        refreshTime = System.currentTimeMillis();
//        for (IndexAction action : actions) {
//          if (action.getIndex() != null &&
//            !refreshedIndexes.contains(action.getIndex().getIndexName())) {
//            refreshedIndexes.add(action.getIndex().getIndexName());
//            action.getIndex().refresh();
//            refreshes.add(action.getIndex().getIndexName());
//          }
//        }
//        refreshTime = System.currentTimeMillis() - refreshTime;
//      } catch (InterruptedException e) {
//        throw new IllegalStateException("ES update has been interrupted", e);
//      }
//
//      basicProfile.stop("INDEX - time:%sms (%sms index, %sms embedded, %sms refresh)\ttypes:[%s],\tbulk:%s\tembedded:%s\trefresh:[%s]",
//        (System.currentTimeMillis() - all_start), indexTime, embeddedTime, refreshTime,
//        StringUtils.join(types, ","),
//        bcount, ecount, StringUtils.join(refreshes, ","));
//    }


}