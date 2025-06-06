/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.iotdb.db.storageengine.dataregion.compaction.selector.impl;

import org.apache.iotdb.commons.conf.IoTDBConstant;
import org.apache.iotdb.commons.utils.CommonDateTimeUtils;
import org.apache.iotdb.db.conf.IoTDBConfig;
import org.apache.iotdb.db.conf.IoTDBDescriptor;
import org.apache.iotdb.db.queryengine.plan.analyze.cache.schema.DataNodeTTLCache;
import org.apache.iotdb.db.storageengine.dataregion.compaction.execute.performer.ICompactionPerformer;
import org.apache.iotdb.db.storageengine.dataregion.compaction.execute.task.SettleCompactionTask;
import org.apache.iotdb.db.storageengine.dataregion.compaction.execute.utils.CompactionUtils;
import org.apache.iotdb.db.storageengine.dataregion.compaction.schedule.CompactionScheduleContext;
import org.apache.iotdb.db.storageengine.dataregion.compaction.selector.ISettleSelector;
import org.apache.iotdb.db.storageengine.dataregion.modification.ModEntry;
import org.apache.iotdb.db.storageengine.dataregion.tsfile.TsFileManager;
import org.apache.iotdb.db.storageengine.dataregion.tsfile.TsFileResource;
import org.apache.iotdb.db.storageengine.dataregion.tsfile.TsFileResourceStatus;
import org.apache.iotdb.db.storageengine.dataregion.tsfile.timeindex.ArrayDeviceTimeIndex;
import org.apache.iotdb.db.storageengine.dataregion.tsfile.timeindex.FileTimeIndex;
import org.apache.iotdb.db.storageengine.dataregion.tsfile.timeindex.ITimeIndex;
import org.apache.iotdb.db.utils.ModificationUtils;

import org.apache.tsfile.file.metadata.IDeviceID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.apache.iotdb.db.storageengine.dataregion.compaction.selector.impl.SettleSelectorImpl.DirtyStatus.NOT_SATISFIED;
import static org.apache.iotdb.db.storageengine.dataregion.compaction.selector.impl.SettleSelectorImpl.DirtyStatus.PARTIALLY_DIRTY;

public class SettleSelectorImpl implements ISettleSelector {
  private static final Logger LOGGER =
      LoggerFactory.getLogger(IoTDBConstant.COMPACTION_LOGGER_NAME);
  private static final IoTDBConfig config = IoTDBDescriptor.getInstance().getConfig();

  // this parameter indicates whether to use the costly method for settle file selection. The
  // high-cost selection has a lower triggering frequency, while the low-cost selection has a higher
  // triggering frequency.
  private final boolean heavySelect;
  private final String storageGroupName;
  private final String dataRegionId;
  private final long timePartition;
  private final TsFileManager tsFileManager;
  private boolean isSeq;
  private final CompactionScheduleContext context;

  public SettleSelectorImpl(
      boolean heavySelect,
      String storageGroupName,
      String dataRegionId,
      long timePartition,
      TsFileManager tsFileManager,
      CompactionScheduleContext context) {
    this.heavySelect = heavySelect;
    this.storageGroupName = storageGroupName;
    this.dataRegionId = dataRegionId;
    this.timePartition = timePartition;
    this.tsFileManager = tsFileManager;
    this.context = context;
  }

  static class FileDirtyInfo {
    DirtyStatus status;
    long dirtyDataSize = 0;

    public FileDirtyInfo(DirtyStatus status) {
      this.status = status;
    }

    public FileDirtyInfo(DirtyStatus status, long dirtyDataSize) {
      this.status = status;
      this.dirtyDataSize = dirtyDataSize;
    }
  }

  static class SettleTaskResource {

    List<TsFileResource> fullyDirtyResources = new ArrayList<>();
    List<TsFileResource> partiallyDirtyResources = new ArrayList<>();
    long totalPartiallyDirtyFileSize = 0;

    public void addFullyDirtyResource(TsFileResource resource) {
      fullyDirtyResources.add(resource);
    }

    public boolean addPartiallyDirtyResource(TsFileResource resource, long dirtyDataSize) {
      partiallyDirtyResources.add(resource);
      totalPartiallyDirtyFileSize += resource.getTsFileSize();
      totalPartiallyDirtyFileSize -= dirtyDataSize;
      return checkHasReachedThreshold();
    }

    public List<TsFileResource> getFullyDirtyResources() {
      return fullyDirtyResources;
    }

    public List<TsFileResource> getPartiallyDirtyResources() {
      return partiallyDirtyResources;
    }

    public boolean checkHasReachedThreshold() {
      return partiallyDirtyResources.size() >= config.getInnerCompactionCandidateFileNum()
          || totalPartiallyDirtyFileSize >= config.getTargetCompactionFileSize();
    }

    public boolean isEmpty() {
      return fullyDirtyResources.isEmpty() && partiallyDirtyResources.isEmpty();
    }
  }

  @Override
  public List<SettleCompactionTask> selectSettleTask(List<TsFileResource> tsFileResources) {
    if (tsFileResources.isEmpty()) {
      return Collections.emptyList();
    }
    this.isSeq = tsFileResources.get(0).isSeq();
    return selectTasks(tsFileResources);
  }

  private List<SettleCompactionTask> selectTasks(List<TsFileResource> resources) {
    List<SettleTaskResource> partiallyDirtyResourceList = new ArrayList<>();
    SettleTaskResource settleTaskResource = new SettleTaskResource();
    try {
      for (TsFileResource resource : resources) {
        boolean shouldStop = false;
        FileDirtyInfo fileDirtyInfo;
        if (resource.getStatus() != TsFileResourceStatus.NORMAL
            || !resource.getTsFileRepairStatus().isNormalCompactionCandidate()) {
          fileDirtyInfo = new FileDirtyInfo(NOT_SATISFIED);
        } else {
          if (!heavySelect) {
            fileDirtyInfo = selectFileBaseOnModSize(resource);
          } else {
            fileDirtyInfo = selectFileBaseOnDirtyData(resource);
          }
        }

        switch (fileDirtyInfo.status) {
          case FULLY_DIRTY:
            settleTaskResource.addFullyDirtyResource(resource);
            break;
          case PARTIALLY_DIRTY:
            shouldStop =
                settleTaskResource.addPartiallyDirtyResource(resource, fileDirtyInfo.dirtyDataSize);
            break;
          case NOT_SATISFIED:
            shouldStop = !settleTaskResource.getPartiallyDirtyResources().isEmpty();
            break;
          default:
            // do nothing
        }

        if (shouldStop) {
          partiallyDirtyResourceList.add(settleTaskResource);
          settleTaskResource = new SettleTaskResource();
          if (!heavySelect) {
            // Non-heavy selection is triggered more frequently. In order to avoid selecting too
            // many files containing mods for compaction when the disk is insufficient, the number
            // and size of files are limited here.
            break;
          }
        }
      }
      partiallyDirtyResourceList.add(settleTaskResource);
      return createTask(partiallyDirtyResourceList);
    } catch (Exception e) {
      LOGGER.error(
          "{}-{} cannot select file for settle compaction", storageGroupName, dataRegionId, e);
    }
    return Collections.emptyList();
  }

  private FileDirtyInfo selectFileBaseOnModSize(TsFileResource resource) {
    long totalModSize = resource.getTotalModSizeInByte();
    if (totalModSize <= 0) {
      return new FileDirtyInfo(DirtyStatus.NOT_SATISFIED);
    }
    return totalModSize > config.getInnerCompactionTaskSelectionModsFileThreshold()
            || !CompactionUtils.isDiskHasSpace(
                config.getInnerCompactionTaskSelectionDiskRedundancy())
        ? new FileDirtyInfo(PARTIALLY_DIRTY)
        : new FileDirtyInfo(DirtyStatus.NOT_SATISFIED);
  }

  /**
   * Only when all devices with ttl are deleted may they be selected. On the basic of the previous,
   * only when the number of deleted devices exceeds the threshold or has expired for too long will
   * they be selected.
   *
   * @return dirty status means the status of current resource.
   */
  @SuppressWarnings("OptionalGetWithoutIsPresent") // iterating the index, must present
  private FileDirtyInfo selectFileBaseOnDirtyData(TsFileResource resource) throws IOException {

    Collection<ModEntry> modifications = resource.getAllModEntries();
    ITimeIndex timeIndex = resource.getTimeIndex();
    if (timeIndex instanceof FileTimeIndex) {
      timeIndex = CompactionUtils.buildDeviceTimeIndex(resource);
    }
    Set<IDeviceID> deletedDevices = new HashSet<>();
    boolean hasExpiredTooLong = false;
    long currentTime = CommonDateTimeUtils.currentTime();

    for (IDeviceID device : ((ArrayDeviceTimeIndex) timeIndex).getDevices()) {
      // check expired device by ttl
      // TODO: remove deviceId conversion

      long ttl;
      String tableName = device.getTableName();
      if (tableName.startsWith("root.")) {
        ttl = DataNodeTTLCache.getInstance().getTTLForTree(device);
      } else {
        ttl = DataNodeTTLCache.getInstance().getTTLForTable(storageGroupName, tableName);
      }
      boolean hasSetTTL = ttl != Long.MAX_VALUE;

      long endTime = timeIndex.getEndTime(device).get();
      boolean isDeleted =
          !timeIndex.isDeviceAlive(device, ttl)
              || isDeviceDeletedByMods(
                  modifications, device, timeIndex.getStartTime(device).get(), endTime);
      if (hasSetTTL) {
        if (!isDeleted) {
          // For devices with TTL set, all data must expire in order to meet the conditions for
          // being selected.
          return new FileDirtyInfo(DirtyStatus.NOT_SATISFIED);
        }

        if (currentTime > endTime) {
          long outdatedTimeDiff = currentTime - endTime;
          if (endTime < 0 && outdatedTimeDiff < currentTime) {
            // overflow, like 100 - Long.MIN
            outdatedTimeDiff = Long.MAX_VALUE;
          }
          long ttlThreshold = 3 * ttl > ttl ? ttl : Long.MAX_VALUE;
          hasExpiredTooLong =
              hasExpiredTooLong
                  || outdatedTimeDiff > Math.min(config.getMaxExpiredTime(), ttlThreshold);
        } // else hasExpiredTooLong unchanged
      }

      if (isDeleted) {
        deletedDevices.add(device);
      }
    }

    double deletedDeviceRatio =
        ((double) deletedDevices.size()) / ((ArrayDeviceTimeIndex) timeIndex).getDevices().size();
    if (deletedDeviceRatio == 1d) {
      // the whole file is completely dirty
      return new FileDirtyInfo(DirtyStatus.FULLY_DIRTY);
    }
    hasExpiredTooLong = config.getMaxExpiredTime() != Long.MAX_VALUE && hasExpiredTooLong;
    if (hasExpiredTooLong || deletedDeviceRatio >= config.getExpiredDataRatio()) {
      // evaluate dirty data size in the tsfile
      return new FileDirtyInfo(
          PARTIALLY_DIRTY, (long) (deletedDeviceRatio * resource.getTsFileSize()));
    }
    return new FileDirtyInfo(DirtyStatus.NOT_SATISFIED);
  }

  /** Check whether the device is completely deleted by mods or not. */
  private boolean isDeviceDeletedByMods(
      Collection<ModEntry> modifications, IDeviceID device, long startTime, long endTime) {
    return ModificationUtils.isAllDeletedByMods(modifications, device, startTime, endTime);
  }

  private List<SettleCompactionTask> createTask(List<SettleTaskResource> settleTaskResourceList) {
    List<SettleCompactionTask> tasks = new ArrayList<>();
    for (SettleTaskResource settleTaskResource : settleTaskResourceList) {
      if (settleTaskResource.isEmpty()) {
        continue;
      }
      SettleCompactionTask task =
          new SettleCompactionTask(
              timePartition,
              tsFileManager,
              settleTaskResource.getFullyDirtyResources(),
              settleTaskResource.getPartiallyDirtyResources(),
              isSeq,
              createCompactionPerformer(),
              tsFileManager.getNextCompactionTaskId());
      tasks.add(task);
    }
    return tasks;
  }

  private ICompactionPerformer createCompactionPerformer() {
    return isSeq ? context.getSeqCompactionPerformer() : context.getUnseqCompactionPerformer();
  }

  enum DirtyStatus {
    FULLY_DIRTY, // the whole file is dirty
    PARTIALLY_DIRTY, // the file is partial dirty
    NOT_SATISFIED; // do not satisfy settle condition, which does not mean there is no dirty data
  }
}
