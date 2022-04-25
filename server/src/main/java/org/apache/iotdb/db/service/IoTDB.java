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
package org.apache.iotdb.db.service;

import org.apache.iotdb.commons.concurrent.IoTDBDefaultThreadExceptionHandler;
import org.apache.iotdb.commons.conf.IoTDBConstant;
import org.apache.iotdb.commons.exception.ConfigurationException;
import org.apache.iotdb.commons.exception.StartupException;
import org.apache.iotdb.commons.service.JMXService;
import org.apache.iotdb.commons.service.RegisterManager;
import org.apache.iotdb.commons.service.StartupChecks;
import org.apache.iotdb.db.conf.IoTDBConfigCheck;
import org.apache.iotdb.db.conf.IoTDBDescriptor;
import org.apache.iotdb.db.conf.rest.IoTDBRestServiceCheck;
import org.apache.iotdb.db.conf.rest.IoTDBRestServiceDescriptor;
import org.apache.iotdb.db.engine.StorageEngine;
import org.apache.iotdb.db.engine.StorageEngineV2;
import org.apache.iotdb.db.engine.cache.CacheHitRatioMonitor;
import org.apache.iotdb.db.engine.compaction.CompactionTaskManager;
import org.apache.iotdb.db.engine.cq.ContinuousQueryService;
import org.apache.iotdb.db.engine.flush.FlushManager;
import org.apache.iotdb.db.engine.trigger.service.TriggerRegistrationService;
import org.apache.iotdb.db.exception.query.QueryProcessException;
import org.apache.iotdb.db.metadata.LocalConfigNode;
import org.apache.iotdb.db.metadata.LocalSchemaProcessor;
import org.apache.iotdb.db.mpp.buffer.DataBlockService;
import org.apache.iotdb.db.mpp.schedule.FragmentInstanceScheduler;
import org.apache.iotdb.db.protocol.influxdb.meta.InfluxDBMetaManager;
import org.apache.iotdb.db.protocol.rest.RestService;
import org.apache.iotdb.db.query.udf.service.TemporaryQueryDataFileService;
import org.apache.iotdb.db.query.udf.service.UDFClassLoaderManager;
import org.apache.iotdb.db.query.udf.service.UDFRegistrationService;
import org.apache.iotdb.db.rescon.PrimitiveArrayManager;
import org.apache.iotdb.db.rescon.SystemInfo;
import org.apache.iotdb.db.service.basic.ServiceProvider;
import org.apache.iotdb.db.service.basic.StandaloneServiceProvider;
import org.apache.iotdb.db.service.metrics.MetricsService;
import org.apache.iotdb.db.service.thrift.impl.DataNodeTSIServiceImpl;
import org.apache.iotdb.db.sync.receiver.ReceiverService;
import org.apache.iotdb.db.sync.sender.service.SenderService;
import org.apache.iotdb.db.wal.WALManager;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

public class IoTDB implements IoTDBMBean {

  private static final Logger logger = LoggerFactory.getLogger(IoTDB.class);
  private final String mbeanName =
      String.format("%s:%s=%s", IoTDBConstant.IOTDB_PACKAGE, IoTDBConstant.JMX_TYPE, "IoTDB");
  private static final RegisterManager registerManager = new RegisterManager();
  public static LocalSchemaProcessor schemaProcessor = LocalSchemaProcessor.getInstance();
  public static LocalConfigNode configManager = LocalConfigNode.getInstance();
  public static ServiceProvider serviceProvider;
  private static boolean clusterMode = false;

  public static IoTDB getInstance() {
    return IoTDBHolder.INSTANCE;
  }

  public static void main(String[] args) {
    try {
      IoTDBConfigCheck.getInstance().checkConfig();
      IoTDBRestServiceCheck.getInstance().checkConfig();
    } catch (ConfigurationException | IOException e) {
      logger.error("meet error when doing start checking", e);
      System.exit(1);
    }
    IoTDB daemon = IoTDB.getInstance();
    daemon.active();
  }

  public static void setSchemaProcessor(LocalSchemaProcessor schemaProcessor) {
    IoTDB.schemaProcessor = schemaProcessor;
  }

  public static void setServiceProvider(ServiceProvider serviceProvider) {
    IoTDB.serviceProvider = serviceProvider;
  }

  public static void setClusterMode() {
    IoTDB.clusterMode = true;
  }

  public static boolean isClusterMode() {
    return IoTDB.clusterMode;
  }

  public void active() {
    StartupChecks checks = new StartupChecks().withDefaultTest();
    try {
      checks.verify();
    } catch (StartupException e) {
      // TODO: what are some checks
      logger.error(
          "{}: failed to start because some checks failed. ", IoTDBConstant.GLOBAL_DB_NAME, e);
      return;
    }
    try {
      setUp();
    } catch (StartupException | QueryProcessException e) {
      logger.error("meet error while starting up.", e);
      deactivate();
      logger.error("{} exit", IoTDBConstant.GLOBAL_DB_NAME);
      return;
    }
    logger.info("{} has started.", IoTDBConstant.GLOBAL_DB_NAME);
  }

  private void setUp() throws StartupException, QueryProcessException {
    logger.info("Setting up IoTDB...");

    Runtime.getRuntime().addShutdownHook(new IoTDBShutdownHook());
    setUncaughtExceptionHandler();
    initServiceProvider();
    registerManager.register(MetricsService.getInstance());
    logger.info("recover the schema...");
    initConfigManager();
    registerManager.register(new JMXService());
    registerManager.register(FlushManager.getInstance());
    registerManager.register(CacheHitRatioMonitor.getInstance());
    registerManager.register(CompactionTaskManager.getInstance());
    JMXService.registerMBean(getInstance(), mbeanName);
    registerManager.register(WALManager.getInstance());

    // in mpp mode we need to start some other services
    if (IoTDBDescriptor.getInstance().getConfig().isMppMode()) {
      registerManager.register(StorageEngineV2.getInstance());
      registerManager.register(DataBlockService.getInstance());
      registerManager.register(InternalService.getInstance());
      registerManager.register(FragmentInstanceScheduler.getInstance());
      IoTDBDescriptor.getInstance()
          .getConfig()
          .setRpcImplClassName(DataNodeTSIServiceImpl.class.getName());
    } else {
      registerManager.register(StorageEngine.getInstance());
    }

    registerManager.register(TemporaryQueryDataFileService.getInstance());
    registerManager.register(UDFClassLoaderManager.getInstance());
    registerManager.register(UDFRegistrationService.getInstance());
    registerManager.register(ReceiverService.getInstance());
    registerManager.register(MetricsService.getInstance());

    // in cluster mode, RPC service is not enabled.
    if (IoTDBDescriptor.getInstance().getConfig().isEnableRpcService()) {
      registerManager.register(RPCService.getInstance());
    }

    initProtocols();
    // in cluster mode, InfluxDBMManager has been initialized, so there is no need to init again to
    // avoid wasting time.
    if (!isClusterMode()
        && IoTDBDescriptor.getInstance().getConfig().isEnableInfluxDBRpcService()) {
      initInfluxDBMManager();
    }

    logger.info("IoTDB is set up, now may some sgs are not ready, please wait several seconds...");

    while (IoTDBDescriptor.getInstance().getConfig().isMppMode()
        ? !StorageEngineV2.getInstance().isAllSgReady()
        : !StorageEngine.getInstance().isAllSgReady()) {
      try {
        Thread.sleep(1000);
      } catch (InterruptedException e) {
        logger.warn("IoTDB failed to set up.", e);
        Thread.currentThread().interrupt();
        return;
      }
    }

    registerManager.register(SenderService.getInstance());
    registerManager.register(UpgradeSevice.getINSTANCE());
    // in mpp mode we temporarily don't start settle service because it uses StorageEngine directly
    // in itself, but currently we need to use StorageEngineV2 instead of StorageEngine in mpp mode.
    if (!IoTDBDescriptor.getInstance().getConfig().isMppMode()) {
      registerManager.register(SettleService.getINSTANCE());
    }
    registerManager.register(TriggerRegistrationService.getInstance());
    registerManager.register(ContinuousQueryService.getInstance());

    // start reporter
    MetricsService.getInstance().startAllReporter();

    logger.info("Congratulation, IoTDB is set up successfully. Now, enjoy yourself!");
  }

  public static void initInfluxDBMManager() {
    InfluxDBMetaManager.getInstance().recover();
  }

  private void initServiceProvider() throws QueryProcessException {
    if (!clusterMode) {
      serviceProvider = new StandaloneServiceProvider();
    }
  }

  public static void initProtocols() throws StartupException {
    if (IoTDBDescriptor.getInstance().getConfig().isEnableInfluxDBRpcService()) {
      registerManager.register(InfluxDBRPCService.getInstance());
    }
    if (IoTDBDescriptor.getInstance().getConfig().isEnableMQTTService()) {
      registerManager.register(MQTTService.getInstance());
    }
    if (IoTDBRestServiceDescriptor.getInstance().getConfig().isEnableRestService()) {
      registerManager.register(RestService.getInstance());
    }
  }

  private void deactivate() {
    logger.info("Deactivating IoTDB...");
    registerManager.deregisterAll();
    JMXService.deregisterMBean(mbeanName);
    logger.info("IoTDB is deactivated.");
  }

  private void initConfigManager() {
    long time = System.currentTimeMillis();
    IoTDB.configManager.init();
    long end = System.currentTimeMillis() - time;
    logger.info("spend {}ms to recover schema.", end);
    logger.info(
        "After initializing, sequence tsFile threshold is {}, unsequence tsFile threshold is {}, memtableSize is {}",
        IoTDBDescriptor.getInstance().getConfig().getSeqTsFileSize(),
        IoTDBDescriptor.getInstance().getConfig().getUnSeqTsFileSize(),
        IoTDBDescriptor.getInstance().getConfig().getMemtableSizeThreshold());
  }

  @Override
  public void stop() {
    deactivate();
  }

  public void shutdown() throws Exception {
    // TODO shutdown is not equal to stop()
    logger.info("Deactivating IoTDB...");
    registerManager.shutdownAll();
    PrimitiveArrayManager.close();
    SystemInfo.getInstance().close();
    JMXService.deregisterMBean(mbeanName);
    logger.info("IoTDB is deactivated.");
  }

  private void setUncaughtExceptionHandler() {
    Thread.setDefaultUncaughtExceptionHandler(new IoTDBDefaultThreadExceptionHandler());
  }

  private static class IoTDBHolder {

    private static final IoTDB INSTANCE = new IoTDB();

    private IoTDBHolder() {}
  }
}
