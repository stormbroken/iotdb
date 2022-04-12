/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.iotdb.db.engine.compaction.cross;

import org.apache.iotdb.db.engine.compaction.task.AbstractCompactionSelector;
import org.apache.iotdb.db.engine.storagegroup.TsFileManager;
import org.apache.iotdb.db.engine.storagegroup.TsFileResource;

import java.util.List;

public abstract class AbstractCrossSpaceCompactionSelector extends AbstractCompactionSelector {
  protected String logicalStorageGroupName;
  protected String dataRegionId;
  protected String storageGroupDir;
  protected long timePartition;
  protected TsFileManager tsFileManager;
  protected List<TsFileResource> sequenceFileList;
  protected List<TsFileResource> unsequenceFileList;
  protected CrossSpaceCompactionTaskFactory taskFactory;

  public AbstractCrossSpaceCompactionSelector(
      String logicalStorageGroupName,
      String dataRegionId,
      String storageGroupDir,
      long timePartition,
      TsFileManager tsFileManager,
      CrossSpaceCompactionTaskFactory taskFactory) {
    this.logicalStorageGroupName = logicalStorageGroupName;
    this.dataRegionId = dataRegionId;
    this.storageGroupDir = storageGroupDir;
    this.timePartition = timePartition;
    this.sequenceFileList = tsFileManager.getSequenceListByTimePartition(timePartition);
    this.unsequenceFileList = tsFileManager.getUnsequenceListByTimePartition(timePartition);
    this.taskFactory = taskFactory;
    this.tsFileManager = tsFileManager;
  }

  @Override
  public abstract void selectAndSubmit();
}
