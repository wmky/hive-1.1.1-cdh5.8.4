/**
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
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.hive.metastore;

import com.facebook.fb303.FacebookBase;
import com.facebook.fb303.fb_status;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableListMultimap;
import com.google.common.collect.Lists;
import com.google.common.collect.Multimaps;

import org.apache.commons.cli.OptionBuilder;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.hive.common.metrics.common.MetricsVariable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hive.common.FileUtils;
import org.apache.hadoop.hive.common.JvmPauseMonitor;
import org.apache.hadoop.hive.common.LogUtils;
import org.apache.hadoop.hive.common.LogUtils.LogInitializationException;
import org.apache.hadoop.hive.common.classification.InterfaceAudience;
import org.apache.hadoop.hive.common.classification.InterfaceStability;
import org.apache.hadoop.hive.common.cli.CommonCliOptions;
import org.apache.hadoop.hive.common.metrics.common.Metrics;
import org.apache.hadoop.hive.common.metrics.common.MetricsConstant;
import org.apache.hadoop.hive.common.metrics.common.MetricsFactory;
import org.apache.hadoop.hive.conf.HiveConf;
import org.apache.hadoop.hive.conf.HiveConf.ConfVars;
import org.apache.hadoop.hive.metastore.api.AbortTxnRequest;
import org.apache.hadoop.hive.metastore.api.AddPartitionsRequest;
import org.apache.hadoop.hive.metastore.api.AddPartitionsResult;
import org.apache.hadoop.hive.metastore.api.AggrStats;
import org.apache.hadoop.hive.metastore.api.AlreadyExistsException;
import org.apache.hadoop.hive.metastore.api.CheckLockRequest;
import org.apache.hadoop.hive.metastore.api.ColumnStatistics;
import org.apache.hadoop.hive.metastore.api.ColumnStatisticsDesc;
import org.apache.hadoop.hive.metastore.api.ColumnStatisticsObj;
import org.apache.hadoop.hive.metastore.api.CommitTxnRequest;
import org.apache.hadoop.hive.metastore.api.CompactionRequest;
import org.apache.hadoop.hive.metastore.api.ConfigValSecurityException;
import org.apache.hadoop.hive.metastore.api.CurrentNotificationEventId;
import org.apache.hadoop.hive.metastore.api.Database;
import org.apache.hadoop.hive.metastore.api.DropPartitionsExpr;
import org.apache.hadoop.hive.metastore.api.DropPartitionsRequest;
import org.apache.hadoop.hive.metastore.api.DropPartitionsResult;
import org.apache.hadoop.hive.metastore.api.EnvironmentContext;
import org.apache.hadoop.hive.metastore.api.FieldSchema;
import org.apache.hadoop.hive.metastore.api.FireEventRequest;
import org.apache.hadoop.hive.metastore.api.FireEventResponse;
import org.apache.hadoop.hive.metastore.api.Function;
import org.apache.hadoop.hive.metastore.api.GetAllFunctionsResponse;
import org.apache.hadoop.hive.metastore.api.GetOpenTxnsInfoResponse;
import org.apache.hadoop.hive.metastore.api.GetOpenTxnsResponse;
import org.apache.hadoop.hive.metastore.api.GetPrincipalsInRoleRequest;
import org.apache.hadoop.hive.metastore.api.GetPrincipalsInRoleResponse;
import org.apache.hadoop.hive.metastore.api.GetRoleGrantsForPrincipalRequest;
import org.apache.hadoop.hive.metastore.api.GetRoleGrantsForPrincipalResponse;
import org.apache.hadoop.hive.metastore.api.GrantRevokePrivilegeRequest;
import org.apache.hadoop.hive.metastore.api.GrantRevokePrivilegeResponse;
import org.apache.hadoop.hive.metastore.api.GrantRevokeRoleRequest;
import org.apache.hadoop.hive.metastore.api.GrantRevokeRoleResponse;
import org.apache.hadoop.hive.metastore.api.HeartbeatRequest;
import org.apache.hadoop.hive.metastore.api.HeartbeatTxnRangeRequest;
import org.apache.hadoop.hive.metastore.api.HeartbeatTxnRangeResponse;
import org.apache.hadoop.hive.metastore.api.HiveObjectPrivilege;
import org.apache.hadoop.hive.metastore.api.HiveObjectRef;
import org.apache.hadoop.hive.metastore.api.HiveObjectType;
import org.apache.hadoop.hive.metastore.api.Index;
import org.apache.hadoop.hive.metastore.api.InvalidInputException;
import org.apache.hadoop.hive.metastore.api.InvalidObjectException;
import org.apache.hadoop.hive.metastore.api.InvalidOperationException;
import org.apache.hadoop.hive.metastore.api.InvalidPartitionException;
import org.apache.hadoop.hive.metastore.api.LockRequest;
import org.apache.hadoop.hive.metastore.api.LockResponse;
import org.apache.hadoop.hive.metastore.api.MetaException;
import org.apache.hadoop.hive.metastore.api.NoSuchLockException;
import org.apache.hadoop.hive.metastore.api.NoSuchObjectException;
import org.apache.hadoop.hive.metastore.api.NoSuchTxnException;
import org.apache.hadoop.hive.metastore.api.NotificationEventRequest;
import org.apache.hadoop.hive.metastore.api.NotificationEventResponse;
import org.apache.hadoop.hive.metastore.api.OpenTxnRequest;
import org.apache.hadoop.hive.metastore.api.OpenTxnsResponse;
import org.apache.hadoop.hive.metastore.api.Partition;
import org.apache.hadoop.hive.metastore.api.PartitionEventType;
import org.apache.hadoop.hive.metastore.api.PartitionListComposingSpec;
import org.apache.hadoop.hive.metastore.api.PartitionSpec;
import org.apache.hadoop.hive.metastore.api.PartitionSpecWithSharedSD;
import org.apache.hadoop.hive.metastore.api.PartitionWithoutSD;
import org.apache.hadoop.hive.metastore.api.PartitionsByExprRequest;
import org.apache.hadoop.hive.metastore.api.PartitionsByExprResult;
import org.apache.hadoop.hive.metastore.api.PartitionsStatsRequest;
import org.apache.hadoop.hive.metastore.api.PartitionsStatsResult;
import org.apache.hadoop.hive.metastore.api.PrincipalPrivilegeSet;
import org.apache.hadoop.hive.metastore.api.PrincipalType;
import org.apache.hadoop.hive.metastore.api.PrivilegeBag;
import org.apache.hadoop.hive.metastore.api.PrivilegeGrantInfo;
import org.apache.hadoop.hive.metastore.api.RequestPartsSpec;
import org.apache.hadoop.hive.metastore.api.Role;
import org.apache.hadoop.hive.metastore.api.RolePrincipalGrant;
import org.apache.hadoop.hive.metastore.api.SetPartitionsStatsRequest;
import org.apache.hadoop.hive.metastore.api.ShowCompactRequest;
import org.apache.hadoop.hive.metastore.api.ShowCompactResponse;
import org.apache.hadoop.hive.metastore.api.ShowLocksRequest;
import org.apache.hadoop.hive.metastore.api.ShowLocksResponse;
import org.apache.hadoop.hive.metastore.api.SkewedInfo;
import org.apache.hadoop.hive.metastore.api.StorageDescriptor;
import org.apache.hadoop.hive.metastore.api.Table;
import org.apache.hadoop.hive.metastore.api.TableMeta;
import org.apache.hadoop.hive.metastore.api.TableStatsRequest;
import org.apache.hadoop.hive.metastore.api.TableStatsResult;
import org.apache.hadoop.hive.metastore.api.ThriftHiveMetastore;
import org.apache.hadoop.hive.metastore.api.TxnAbortedException;
import org.apache.hadoop.hive.metastore.api.TxnOpenException;
import org.apache.hadoop.hive.metastore.api.Type;
import org.apache.hadoop.hive.metastore.api.UnknownDBException;
import org.apache.hadoop.hive.metastore.api.UnknownPartitionException;
import org.apache.hadoop.hive.metastore.api.UnknownTableException;
import org.apache.hadoop.hive.metastore.api.UnlockRequest;
import org.apache.hadoop.hive.metastore.api.hive_metastoreConstants;
import org.apache.hadoop.hive.metastore.events.AddIndexEvent;
import org.apache.hadoop.hive.metastore.events.AddPartitionEvent;
import org.apache.hadoop.hive.metastore.events.AlterIndexEvent;
import org.apache.hadoop.hive.metastore.events.AlterPartitionEvent;
import org.apache.hadoop.hive.metastore.events.AlterTableEvent;
import org.apache.hadoop.hive.metastore.events.ConfigChangeEvent;
import org.apache.hadoop.hive.metastore.events.CreateDatabaseEvent;
import org.apache.hadoop.hive.metastore.events.CreateTableEvent;
import org.apache.hadoop.hive.metastore.events.DropDatabaseEvent;
import org.apache.hadoop.hive.metastore.events.DropIndexEvent;
import org.apache.hadoop.hive.metastore.events.DropPartitionEvent;
import org.apache.hadoop.hive.metastore.events.DropTableEvent;
import org.apache.hadoop.hive.metastore.events.EventCleanerTask;
import org.apache.hadoop.hive.metastore.events.InsertEvent;
import org.apache.hadoop.hive.metastore.events.LoadPartitionDoneEvent;
import org.apache.hadoop.hive.metastore.events.PreAddIndexEvent;
import org.apache.hadoop.hive.metastore.events.PreAddPartitionEvent;
import org.apache.hadoop.hive.metastore.events.PreAlterIndexEvent;
import org.apache.hadoop.hive.metastore.events.PreAlterPartitionEvent;
import org.apache.hadoop.hive.metastore.events.PreAlterTableEvent;
import org.apache.hadoop.hive.metastore.events.PreAuthorizationCallEvent;
import org.apache.hadoop.hive.metastore.events.PreCreateDatabaseEvent;
import org.apache.hadoop.hive.metastore.events.PreCreateTableEvent;
import org.apache.hadoop.hive.metastore.events.PreDropDatabaseEvent;
import org.apache.hadoop.hive.metastore.events.PreDropIndexEvent;
import org.apache.hadoop.hive.metastore.events.PreDropPartitionEvent;
import org.apache.hadoop.hive.metastore.events.PreDropTableEvent;
import org.apache.hadoop.hive.metastore.events.PreEventContext;
import org.apache.hadoop.hive.metastore.events.PreLoadPartitionDoneEvent;
import org.apache.hadoop.hive.metastore.events.PreReadDatabaseEvent;
import org.apache.hadoop.hive.metastore.events.PreReadTableEvent;
import org.apache.hadoop.hive.metastore.model.MDBPrivilege;
import org.apache.hadoop.hive.metastore.model.MGlobalPrivilege;
import org.apache.hadoop.hive.metastore.model.MPartitionColumnPrivilege;
import org.apache.hadoop.hive.metastore.model.MPartitionPrivilege;
import org.apache.hadoop.hive.metastore.model.MRole;
import org.apache.hadoop.hive.metastore.model.MRoleMap;
import org.apache.hadoop.hive.metastore.model.MTableColumnPrivilege;
import org.apache.hadoop.hive.metastore.model.MTablePrivilege;
import org.apache.hadoop.hive.metastore.partition.spec.PartitionSpecProxy;
import org.apache.hadoop.hive.metastore.txn.TxnHandler;
import org.apache.hadoop.hive.serde2.Deserializer;
import org.apache.hadoop.hive.serde2.SerDeException;
import org.apache.hadoop.hive.shims.HadoopShims;
import org.apache.hadoop.hive.shims.ShimLoader;
import org.apache.hadoop.hive.shims.Utils;
import org.apache.hadoop.hive.thrift.HadoopThriftAuthBridge;
import org.apache.hadoop.hive.thrift.HadoopThriftAuthBridge.Server.ServerMode;
import org.apache.hadoop.hive.thrift.TUGIContainingTransport;
import org.apache.hadoop.security.UserGroupInformation;
import org.apache.hadoop.util.ReflectionUtils;
import org.apache.hadoop.util.StringUtils;
import org.apache.hive.common.util.HiveStringUtils;
import org.apache.thrift.TException;
import org.apache.thrift.TProcessor;
import org.apache.thrift.protocol.TBinaryProtocol;
import org.apache.thrift.protocol.TCompactProtocol;
import org.apache.thrift.protocol.TProtocol;
import org.apache.thrift.protocol.TProtocolFactory;
import org.apache.thrift.server.ServerContext;
import org.apache.thrift.server.TServer;
import org.apache.thrift.server.TServerEventHandler;
import org.apache.thrift.server.TThreadPoolServer;
import org.apache.thrift.transport.TFramedTransport;
import org.apache.thrift.transport.TServerSocket;
import org.apache.thrift.transport.TServerTransport;
import org.apache.thrift.transport.TTransport;
import org.apache.thrift.transport.TTransportFactory;

import com.facebook.fb303.FacebookBase;
import com.facebook.fb303.fb_status;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableListMultimap;
import com.google.common.collect.Lists;
import com.google.common.collect.Multimaps;
import javax.jdo.JDOException;

import java.io.IOException;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Formatter;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Properties;
import java.util.Set;
import java.util.Timer;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;

import static org.apache.commons.lang.StringUtils.join;
import static org.apache.hadoop.hive.metastore.MetaStoreUtils.*;

/**
 * TODO:pc remove application logic to a separate interface.
 */
public class HiveMetaStore extends ThriftHiveMetastore {
  public static final Log LOG = LogFactory.getLog(HiveMetaStore.class);

  // boolean that tells if the HiveMetaStore (remote) server is being used.
  // Can be used to determine if the calls to metastore api (HMSHandler) are being made with
  // embedded metastore or a remote one
  private static boolean isMetaStoreRemote = false;

  // Used for testing to simulate method timeout.
  @VisibleForTesting
  static boolean TEST_TIMEOUT_ENABLED = false;
  @VisibleForTesting
  static long TEST_TIMEOUT_VALUE = -1;

  /** A fixed date format to be used for hive partition column values. */
  public static final ThreadLocal<DateFormat> PARTITION_DATE_FORMAT =
       new ThreadLocal<DateFormat>() {
    @Override
    protected DateFormat initialValue() {
      DateFormat val = new SimpleDateFormat("yyyy-MM-dd");
      val.setLenient(false); // Without this, 2020-20-20 becomes 2021-08-20.
      return val;
    };
  };

  /**
   * default port on which to start the Hive server
   */
  private static final int DEFAULT_HIVE_METASTORE_PORT = 9083;
  public static final String ADMIN = "admin";
  public static final String PUBLIC = "public";

  private static HadoopThriftAuthBridge.Server saslServer;
  private static boolean useSasl;

  public static final String NO_FILTER_STRING = "";
  public static final int UNLIMITED_MAX_PARTITIONS = -1;

  private static final class ChainedTTransportFactory extends TTransportFactory {
    private final TTransportFactory parentTransFactory;
    private final TTransportFactory childTransFactory;

    private ChainedTTransportFactory(
        TTransportFactory parentTransFactory,
        TTransportFactory childTransFactory) {
      this.parentTransFactory = parentTransFactory;
      this.childTransFactory = childTransFactory;
    }

    @Override
    public TTransport getTransport(TTransport trans) {
      return childTransFactory.getTransport(parentTransFactory.getTransport(trans));
    }
  }

  public static class HMSHandler extends FacebookBase implements
      IHMSHandler {
    public static final Log LOG = HiveMetaStore.LOG;
    private String rawStoreClassName;
    private final HiveConf hiveConf; // stores datastore (jpox) properties,
                                     // right now they come from jpox.properties

    private static String currentUrl;

    //For Metrics
    private int initDatabaseCount, initTableCount, initPartCount;

    private Warehouse wh; // hdfs warehouse
    private static final ThreadLocal<RawStore> threadLocalMS =
        new ThreadLocal<RawStore>() {
          @Override
          protected synchronized RawStore initialValue() {
            return null;
          }
        };

    private static final ThreadLocal<TxnHandler> threadLocalTxn = new ThreadLocal<TxnHandler>() {
      @Override
      protected synchronized TxnHandler initialValue() {
        return null;
      }
    };

    public static RawStore getRawStore() {
      return threadLocalMS.get();
    }

    public static void removeRawStore() {
      threadLocalMS.remove();
    }

    // Thread local configuration is needed as many threads could make changes
    // to the conf using the connection hook
    private static final ThreadLocal<Configuration> threadLocalConf =
        new ThreadLocal<Configuration>() {
          @Override
          protected synchronized Configuration initialValue() {
            return null;
          }
        };

    public static final String AUDIT_FORMAT =
        "ugi=%s\t" + // ugi
            "ip=%s\t" + // remote IP
            "cmd=%s\t"; // command
    public static final Log auditLog = LogFactory.getLog(
        HiveMetaStore.class.getName() + ".audit");
    private static final ThreadLocal<Formatter> auditFormatter =
        new ThreadLocal<Formatter>() {
          @Override
          protected Formatter initialValue() {
            return new Formatter(new StringBuilder(AUDIT_FORMAT.length() * 4));
          }
        };

    private static final void logAuditEvent(String cmd) {
      if (cmd == null) {
        return;
      }

      UserGroupInformation ugi;
      try {
        ugi = Utils.getUGI();
      } catch (Exception ex) {
        throw new RuntimeException(ex);
      }
      final Formatter fmt = auditFormatter.get();
      ((StringBuilder) fmt.out()).setLength(0);

      String address = null;
      if (useSasl) {
        if (saslServer != null && saslServer.getRemoteAddress() != null) {
          address = String.valueOf(saslServer.getRemoteAddress());
        }
      } else {
        address = getIpAddress();
      }
      if (address == null) {
        address = "unknown-ip-addr";
      }

      auditLog.info(fmt.format(AUDIT_FORMAT, ugi.getUserName(),
          address, cmd).toString());
    }

    private static int nextSerialNum = 0;
    private static ThreadLocal<Integer> threadLocalId = new ThreadLocal<Integer>() {
      @Override
      protected synchronized Integer initialValue() {
        return new Integer(nextSerialNum++);
      }
    };

    // This will only be set if the metastore is being accessed from a metastore Thrift server,
    // not if it is from the CLI. Also, only if the TTransport being used to connect is an
    // instance of TSocket.
    private static ThreadLocal<String> threadLocalIpAddress = new ThreadLocal<String>() {
      @Override
      protected synchronized String initialValue() {
        return null;
      }
    };

    public static void setIpAddress(String ipAddress) {
      threadLocalIpAddress.set(ipAddress);
    }

    // This will return null if the metastore is not being accessed from a metastore Thrift server,
    // or if the TTransport being used to connect is not an instance of TSocket.
    public static String getIpAddress() {
      return threadLocalIpAddress.get();
    }

    public static Integer get() {
      return threadLocalId.get();
    }

    public HMSHandler(String name) throws MetaException {
      this(name, new HiveConf(HMSHandler.class), true);
    }

    public HMSHandler(String name, HiveConf conf) throws MetaException {
      this(name, conf, true);
    }

    public HMSHandler(String name, HiveConf conf, boolean init) throws MetaException {
      super(name);
      hiveConf = conf;
      if (init) {
        init();
      }
    }

    public HiveConf getHiveConf() {
      return hiveConf;
    }

    private ClassLoader classLoader;
    private AlterHandler alterHandler;
    private List<MetaStorePreEventListener> preListeners;
    private List<MetaStoreEventListener> listeners;
    private List<MetaStoreEndFunctionListener> endFunctionListeners;
    private List<MetaStoreInitListener> initListeners;
    private Pattern partitionValidationPattern;

    {
      classLoader = Thread.currentThread().getContextClassLoader();
      if (classLoader == null) {
        classLoader = Configuration.class.getClassLoader();
      }
    }

    @Override
    public void init() throws MetaException {
      rawStoreClassName = hiveConf.getVar(HiveConf.ConfVars.METASTORE_RAW_STORE_IMPL);
      initListeners = MetaStoreUtils.getMetaStoreListeners(
          MetaStoreInitListener.class, hiveConf,
          hiveConf.getVar(HiveConf.ConfVars.METASTORE_INIT_HOOKS));
      for (MetaStoreInitListener singleInitListener: initListeners) {
          MetaStoreInitContext context = new MetaStoreInitContext();
          singleInitListener.onInit(context);
      }

      String alterHandlerName = hiveConf.get("hive.metastore.alter.impl",
          HiveAlterHandler.class.getName());
      alterHandler = (AlterHandler) ReflectionUtils.newInstance(MetaStoreUtils.getClass(
          alterHandlerName), hiveConf);
      wh = new Warehouse(hiveConf);

      synchronized (HMSHandler.class) {
        if (currentUrl == null || !currentUrl.equals(MetaStoreInit.getConnectionURL(hiveConf))) {
          createDefaultDB();
          createDefaultRoles();
          addAdminUsers();
          currentUrl = MetaStoreInit.getConnectionURL(hiveConf);
        }
      }

      //Start Metrics for Embedded mode
      if (hiveConf.getBoolVar(ConfVars.METASTORE_METRICS)) {
        try {
          MetricsFactory.init(hiveConf);
        } catch (Exception e) {
          // log exception, but ignore inability to start
          LOG.error("error in Metrics init: " + e.getClass().getName() + " "
              + e.getMessage(), e);
        }
      }

      Metrics metrics = MetricsFactory.getInstance();
      if (metrics != null && hiveConf.getBoolVar(ConfVars.METASTORE_INIT_METADATA_COUNT_ENABLED)) {
        LOG.info("Begin calculating metadata count metrics.");
        updateMetrics();
        LOG.info("Finished metadata count metrics: " + initDatabaseCount + " databases, " + initTableCount +
          " tables, " + initPartCount + " partitions.");
        metrics.addGauge(MetricsConstant.INIT_TOTAL_DATABASES, new MetricsVariable() {
          @Override
          public Object getValue() {
            return initDatabaseCount;
          }
        });
        metrics.addGauge(MetricsConstant.INIT_TOTAL_TABLES, new MetricsVariable() {
          @Override
          public Object getValue() {
            return initTableCount;
          }
        });
        metrics.addGauge(MetricsConstant.INIT_TOTAL_PARTITIONS, new MetricsVariable() {
          @Override
          public Object getValue() {
            return initPartCount;
          }
        });
      }

      preListeners = MetaStoreUtils.getMetaStoreListeners(MetaStorePreEventListener.class,
          hiveConf,
          hiveConf.getVar(HiveConf.ConfVars.METASTORE_PRE_EVENT_LISTENERS));
      listeners = MetaStoreUtils.getMetaStoreListeners(MetaStoreEventListener.class, hiveConf,
          hiveConf.getVar(HiveConf.ConfVars.METASTORE_EVENT_LISTENERS));
      listeners.add(new SessionPropertiesListener(hiveConf));

      if (metrics != null) {
        listeners.add(new HMSMetricsListener(hiveConf, metrics));
      }

      endFunctionListeners = MetaStoreUtils.getMetaStoreListeners(
          MetaStoreEndFunctionListener.class, hiveConf,
          hiveConf.getVar(HiveConf.ConfVars.METASTORE_END_FUNCTION_LISTENERS));

      String partitionValidationRegex =
          hiveConf.getVar(HiveConf.ConfVars.METASTORE_PARTITION_NAME_WHITELIST_PATTERN);
      if (partitionValidationRegex != null && !partitionValidationRegex.isEmpty()) {
        partitionValidationPattern = Pattern.compile(partitionValidationRegex);
      } else {
        partitionValidationPattern = null;
      }

      long cleanFreq = hiveConf.getTimeVar(ConfVars.METASTORE_EVENT_CLEAN_FREQ, TimeUnit.MILLISECONDS);
      if (cleanFreq > 0) {
        // In default config, there is no timer.
        Timer cleaner = new Timer("Metastore Events Cleaner Thread", true);
        cleaner.schedule(new EventCleanerTask(this), cleanFreq, cleanFreq);
      }
    }

    private String addPrefix(String s) {
      return threadLocalId.get() + ": " + s;
    }

    @Override
    public void setConf(Configuration conf) {
      threadLocalConf.set(conf);
      RawStore ms = threadLocalMS.get();
      if (ms != null) {
        ms.setConf(conf); // reload if DS related configuration is changed
      }
    }

    @Override
    public Configuration getConf() {
      Configuration conf = threadLocalConf.get();
      if (conf == null) {
        conf = new Configuration(hiveConf);
        threadLocalConf.set(conf);
      }
      return conf;
    }

    public Warehouse getWh() {
      return wh;
    }

    @Override
    public void setMetaConf(String key, String value) throws MetaException {
      ConfVars confVar = HiveConf.getMetaConf(key);
      if (confVar == null) {
        throw new MetaException("Invalid configuration key " + key);
      }
      String validate = confVar.validate(value);
      if (validate != null) {
        throw new MetaException("Invalid configuration value " + value + " for key " + key +
            " by " + validate);
      }
      Configuration configuration = getConf();
      String oldValue = configuration.get(key);
      configuration.set(key, value);

      for (MetaStoreEventListener listener : listeners) {
        listener.onConfigChange(new ConfigChangeEvent(this, key, oldValue, value));
      }
    }

    @Override
    public String getMetaConf(String key) throws MetaException {
      ConfVars confVar = HiveConf.getMetaConf(key);
      if (confVar == null) {
        throw new MetaException("Invalid configuration key " + key);
      }
      return getConf().get(key);
    }

    /**
     * Get a cached RawStore.
     *
     * @return the cached RawStore
     * @throws MetaException
     */
    @InterfaceAudience.LimitedPrivate({"HCATALOG"})
    @InterfaceStability.Evolving
    public RawStore getMS() throws MetaException {
      RawStore ms = threadLocalMS.get();
      if (ms == null) {
        ms = newRawStore();
        ms.verifySchema();
        threadLocalMS.set(ms);
        ms = threadLocalMS.get();
      }
      return ms;
    }

    private TxnHandler getTxnHandler() {
      TxnHandler txn = threadLocalTxn.get();
      if (txn == null) {
        txn = new TxnHandler(hiveConf);
        threadLocalTxn.set(txn);
      }
      return txn;
    }

    private RawStore newRawStore() throws MetaException {
      LOG.info(addPrefix("Opening raw store with implemenation class:"
          + rawStoreClassName));
      Configuration conf = getConf();

      return RawStoreProxy.getProxy(hiveConf, conf, rawStoreClassName, threadLocalId.get());
    }

    private void createDefaultDB_core(RawStore ms) throws MetaException, InvalidObjectException {
      try {
        ms.getDatabase(DEFAULT_DATABASE_NAME);
      } catch (NoSuchObjectException e) {
        Database db = new Database(DEFAULT_DATABASE_NAME, DEFAULT_DATABASE_COMMENT,
          wh.getDefaultDatabasePath(DEFAULT_DATABASE_NAME).toString(), null);
        db.setOwnerName(PUBLIC);
        db.setOwnerType(PrincipalType.ROLE);
        ms.createDatabase(db);
      }
    }

    /**
     * create default database if it doesn't exist.
     *
     * This is a potential contention when HiveServer2 using embedded metastore and Metastore
     * Server try to concurrently invoke createDefaultDB. If one failed, JDOException was caught
     * for one more time try, if failed again, simply ignored by warning, which meant another
     * succeeds.
     *
     * @throws MetaException
     */
    private void createDefaultDB() throws MetaException {
      try {
        createDefaultDB_core(getMS());
      } catch (JDOException e) {
        LOG.warn("Retrying creating default database after error: " + e.getMessage(), e);
        try {
          createDefaultDB_core(getMS());
        } catch (InvalidObjectException e1) {
          throw new MetaException(e1.getMessage());
        }
      } catch (InvalidObjectException e) {
        throw new MetaException(e.getMessage());
      }
    }

    /**
     * create default roles if they don't exist.
     *
     * This is a potential contention when HiveServer2 using embedded metastore and Metastore
     * Server try to concurrently invoke createDefaultRoles. If one failed, JDOException was caught
     * for one more time try, if failed again, simply ignored by warning, which meant another
     * succeeds.
     *
     * @throws MetaException
     */
    private void createDefaultRoles() throws MetaException {
      try {
        createDefaultRoles_core();
      } catch (JDOException e) {
        LOG.warn("Retrying creating default roles after error: " + e.getMessage(), e);
        createDefaultRoles_core();
      }
    }

    private void createDefaultRoles_core() throws MetaException {

      RawStore ms = getMS();
      try {
        ms.addRole(ADMIN, ADMIN);
      } catch (InvalidObjectException e) {
        LOG.debug(ADMIN +" role already exists",e);
      } catch (NoSuchObjectException e) {
        // This should never be thrown.
        LOG.warn("Unexpected exception while adding " +ADMIN+" roles" , e);
      }
      LOG.info("Added "+ ADMIN+ " role in metastore");
      try {
        ms.addRole(PUBLIC, PUBLIC);
      } catch (InvalidObjectException e) {
        LOG.debug(PUBLIC + " role already exists",e);
      } catch (NoSuchObjectException e) {
        // This should never be thrown.
        LOG.warn("Unexpected exception while adding "+PUBLIC +" roles" , e);
      }
      LOG.info("Added "+PUBLIC+ " role in metastore");
      // now grant all privs to admin
      PrivilegeBag privs = new PrivilegeBag();
      privs.addToPrivileges(new HiveObjectPrivilege( new HiveObjectRef(HiveObjectType.GLOBAL, null,
        null, null, null), ADMIN, PrincipalType.ROLE, new PrivilegeGrantInfo("All", 0, ADMIN,
        PrincipalType.ROLE, true)));
      try {
        ms.grantPrivileges(privs);
      } catch (InvalidObjectException e) {
        // Surprisingly these privs are already granted.
        LOG.debug("Failed while granting global privs to admin", e);
      } catch (NoSuchObjectException e) {
        // Unlikely to be thrown.
        LOG.warn("Failed while granting global privs to admin", e);
      }
    }

    /**
     * add admin users if they don't exist.
     *
     * This is a potential contention when HiveServer2 using embedded metastore and Metastore
     * Server try to concurrently invoke addAdminUsers. If one failed, JDOException was caught for
     * one more time try, if failed again, simply ignored by warning, which meant another succeeds.
     *
     * @throws MetaException
     */
    private void addAdminUsers() throws MetaException {
      try {
        addAdminUsers_core();
      } catch (JDOException e) {
        LOG.warn("Retrying adding admin users after error: " + e.getMessage(), e);
        addAdminUsers_core();
      }
    }

    private void addAdminUsers_core() throws MetaException {

      // now add pre-configured users to admin role
      String userStr = HiveConf.getVar(hiveConf,ConfVars.USERS_IN_ADMIN_ROLE,"").trim();
      if (userStr.isEmpty()) {
        LOG.info("No user is added in admin role, since config is empty");
        return;
      }
      // Since user names need to be valid unix user names, per IEEE Std 1003.1-2001 they cannot
      // contain comma, so we can safely split above string on comma.

     Iterator<String> users = Splitter.on(",").trimResults().omitEmptyStrings().split(userStr).iterator();
      if (!users.hasNext()) {
        LOG.info("No user is added in admin role, since config value "+ userStr +
          " is in incorrect format. We accept comma seprated list of users.");
        return;
      }
      Role adminRole;
      RawStore ms = getMS();
      try {
        adminRole = ms.getRole(ADMIN);
      } catch (NoSuchObjectException e) {
        LOG.error("Failed to retrieve just added admin role",e);
        return;
      }
      while (users.hasNext()) {
        String userName = users.next();
        try {
          ms.grantRole(adminRole, userName, PrincipalType.USER, ADMIN, PrincipalType.ROLE, true);
          LOG.info("Added " + userName + " to admin role");
        } catch (NoSuchObjectException e) {
          LOG.error("Failed to add "+ userName + " in admin role",e);
        } catch (InvalidObjectException e) {
          LOG.debug(userName + " already in admin role", e);
        }
      }
    }

    private static void logInfo(String m) {
      LOG.info(threadLocalId.get().toString() + ": " + m);
      logAuditEvent(m);
    }

    private String startFunction(String function, String extraLogInfo) {
      incrementCounter(function);
      logInfo((getIpAddress() == null ? "" : "source:" + getIpAddress() + " ") +
          function + extraLogInfo);
      if (MetricsFactory.getInstance() != null) {
        try {
          MetricsFactory.getInstance().startStoredScope(function);
        } catch (IOException e) {
          LOG.debug("Exception when starting metrics scope"
            + e.getClass().getName() + " " + e.getMessage(), e);
        }
      }
      return function;
    }

    private String startFunction(String function) {
      return startFunction(function, "");
    }

    private String startTableFunction(String function, String db, String tbl) {
      return startFunction(function, " : db=" + db + " tbl=" + tbl);
    }

    private String startMultiTableFunction(String function, String db, List<String> tbls) {
      String tableNames = join(tbls, ",");
      return startFunction(function, " : db=" + db + " tbls=" + tableNames);
    }

    private String startPartitionFunction(String function, String db, String tbl,
        List<String> partVals) {
      return startFunction(function, " : db=" + db + " tbl=" + tbl
          + "[" + join(partVals, ",") + "]");
    }

    private String startPartitionFunction(String function, String db, String tbl,
        Map<String, String> partName) {
      return startFunction(function, " : db=" + db + " tbl=" + tbl + "partition=" + partName);
    }

    private void endFunction(String function, boolean successful, Exception e) {
      endFunction(function, successful, e, null);
    }
    private void endFunction(String function, boolean successful, Exception e,
                            String inputTableName) {
      endFunction(function, new MetaStoreEndFunctionContext(successful, e, inputTableName));
    }

    private void endFunction(String function, MetaStoreEndFunctionContext context) {
      if (MetricsFactory.getInstance() != null) {
        try {
          MetricsFactory.getInstance().endStoredScope(function);
        } catch (IOException e) {
          LOG.debug("Exception when closing metrics scope" + e);
        }
      }

      for (MetaStoreEndFunctionListener listener : endFunctionListeners) {
        listener.onEndFunction(function, context);
      }
    }

    @Override
    public fb_status getStatus() {
      return fb_status.ALIVE;
    }

    @Override
    public void shutdown() {
      cleanupRawStore();
    }

    @Override
    public AbstractMap<String, Long> getCounters() {
      AbstractMap<String, Long> counters = super.getCounters();

      // Allow endFunctionListeners to add any counters they have collected
      if (endFunctionListeners != null) {
        for (MetaStoreEndFunctionListener listener : endFunctionListeners) {
          listener.exportCounters(counters);
        }
      }

      return counters;
    }

    private void create_database_core(RawStore ms, final Database db)
        throws AlreadyExistsException, InvalidObjectException, MetaException {
      if (!validateName(db.getName())) {
        throw new InvalidObjectException(db.getName() + " is not a valid database name");
      }
      if (null == db.getLocationUri()) {
        db.setLocationUri(wh.getDefaultDatabasePath(db.getName()).toString());
      } else {
        db.setLocationUri(wh.getDnsPath(new Path(db.getLocationUri())).toString());
      }
      Path dbPath = new Path(db.getLocationUri());
      boolean success = false;
      boolean madeDir = false;

      try {

        firePreEvent(new PreCreateDatabaseEvent(db, this));

        if (!wh.isDir(dbPath)) {
          if (!wh.mkdirs(dbPath, true)) {
            throw new MetaException("Unable to create database path " + dbPath +
                ", failed to create database " + db.getName());
          }
          madeDir = true;
        }

        ms.openTransaction();
        ms.createDatabase(db);
        success = ms.commitTransaction();
      } finally {
        if (!success) {
          ms.rollbackTransaction();
          if (madeDir) {
            wh.deleteDir(dbPath, true);
          }
        }
        for (MetaStoreEventListener listener : listeners) {
          listener.onCreateDatabase(new CreateDatabaseEvent(db, success, this));
        }
      }
    }

    @Override
    public void create_database(final Database db)
        throws AlreadyExistsException, InvalidObjectException, MetaException {
      startFunction("create_database", ": " + db.toString());
      boolean success = false;
      Exception ex = null;
      try {
        try {
          if (null != get_database_core(db.getName())) {
            throw new AlreadyExistsException("Database " + db.getName() + " already exists");
          }
        } catch (NoSuchObjectException e) {
          // expected
        }

        if (TEST_TIMEOUT_ENABLED) {
          try {
            Thread.sleep(TEST_TIMEOUT_VALUE);
          } catch (InterruptedException e) {
            // do nothing
          }
          Deadline.checkTimeout();
        }

        create_database_core(getMS(), db);
        success = true;
      } catch (Exception e) {
        ex = e;
        if (e instanceof MetaException) {
          throw (MetaException) e;
        } else if (e instanceof InvalidObjectException) {
          throw (InvalidObjectException) e;
        } else if (e instanceof AlreadyExistsException) {
          throw (AlreadyExistsException) e;
        } else {
          throw newMetaException(e);
        }
      } finally {
        endFunction("create_database", success, ex);
      }
    }

    @Override
    public Database get_database(final String name) throws NoSuchObjectException, MetaException {
      startFunction("get_database", ": " + name);
      Database db = null;
      Exception ex = null;
      try {
        db = get_database_core(name);
        firePreEvent(new PreReadDatabaseEvent(db, this));
      } catch (MetaException e) {
        ex = e;
        throw e;
      } catch (NoSuchObjectException e) {
        ex = e;
        throw e;
      } finally {
        endFunction("get_database", db != null, ex);
      }
      return db;
    }

    /**
     * Equivalent to get_database, but does not write to audit logs, or fire pre-event listners.
     * Meant to be used for internal hive classes that don't use the thrift interface.
     * @param name
     * @return
     * @throws NoSuchObjectException
     * @throws MetaException
     */
    public Database get_database_core(final String name) throws NoSuchObjectException,
        MetaException {
      Database db = null;
      try {
        db = getMS().getDatabase(name);
      } catch (MetaException e) {
        throw e;
      } catch (NoSuchObjectException e) {
        throw e;
      } catch (Exception e) {
        assert (e instanceof RuntimeException);
        throw (RuntimeException) e;
      }
      return db;
    }

    @Override
    public void alter_database(final String dbName, final Database db)
        throws NoSuchObjectException, TException, MetaException {
      startFunction("alter_database" + dbName);
      boolean success = false;
      Exception ex = null;
      try {
        getMS().alterDatabase(dbName, db);
        success = true;
      } catch (Exception e) {
        ex = e;
        rethrowException(e);
      } finally {
        endFunction("alter_database", success, ex);
      }
    }

    private void drop_database_core(RawStore ms,
        final String name, final boolean deleteData, final boolean cascade)
        throws NoSuchObjectException, InvalidOperationException, MetaException,
        IOException, InvalidObjectException, InvalidInputException {
      boolean success = false;
      Database db = null;
      List<Path> tablePaths = new ArrayList<Path>();
      List<Path> partitionPaths = new ArrayList<Path>();
      try {
        ms.openTransaction();
        db = ms.getDatabase(name);

        firePreEvent(new PreDropDatabaseEvent(db, this));

        List<String> allTables = get_all_tables(db.getName());
        List<String> allFunctions = get_functions(db.getName(), "*");

        if (!cascade) {
          if (!allTables.isEmpty()) {
            throw new InvalidOperationException(
                "Database " + db.getName() + " is not empty. One or more tables exist.");
          }
          if (!allFunctions.isEmpty()) {
            throw new InvalidOperationException(
                "Database " + db.getName() + " is not empty. One or more functions exist.");
          }
        }
        Path path = new Path(db.getLocationUri()).getParent();
        if (!wh.isWritable(path)) {
          throw new MetaException("Database not dropped since " +
              path + " is not writable by " +
              hiveConf.getUser());
        }

        Path databasePath = wh.getDnsPath(wh.getDatabasePath(db));

        // drop any functions before dropping db
        for (String funcName : allFunctions) {
          drop_function(name, funcName);
        }

        // drop tables before dropping db
        int tableBatchSize = HiveConf.getIntVar(hiveConf,
            ConfVars.METASTORE_BATCH_RETRIEVE_MAX);

        int startIndex = 0;
        // retrieve the tables from the metastore in batches to alleviate memory constraints
        while (startIndex < allTables.size()) {
          int endIndex = Math.min(startIndex + tableBatchSize, allTables.size());

          List<Table> tables = null;
          try {
            tables = ms.getTableObjectsByName(name, allTables.subList(startIndex, endIndex));
          } catch (UnknownDBException e) {
            throw new MetaException(e.getMessage());
          }

          if (tables != null && !tables.isEmpty()) {
            for (Table table : tables) {

              // If the table is not external and it might not be in a subdirectory of the database
              // add it's locations to the list of paths to delete
              Path tablePath = null;
              if (table.getSd().getLocation() != null && !isExternal(table)) {
                tablePath = wh.getDnsPath(new Path(table.getSd().getLocation()));
                if (!wh.isWritable(tablePath.getParent())) {
                  throw new MetaException("Database metadata not deleted since table: " +
                      table.getTableName() + " has a parent location " + tablePath.getParent() +
                      " which is not writable by " + hiveConf.getUser());
                }

                if (!isSubdirectory(databasePath, tablePath)) {
                  tablePaths.add(tablePath);
                }
              }

              // For each partition in each table, drop the partitions and get a list of
              // partitions' locations which might need to be deleted
              partitionPaths = dropPartitionsAndGetLocations(ms, name, table.getTableName(),
                  tablePath, table.getPartitionKeys(), deleteData && !isExternal(table));

              // Drop the table but not its data
              drop_table(name, table.getTableName(), false);
            }

            startIndex = endIndex;
          }
        }

        if (ms.dropDatabase(name)) {
          success = ms.commitTransaction();
        }
      } finally {
        if (!success) {
          ms.rollbackTransaction();
        } else if (deleteData) {
          // Delete the data in the partitions which have other locations
          deletePartitionData(partitionPaths);
          // Delete the data in the tables which have other locations
          for (Path tablePath : tablePaths) {
            deleteTableData(tablePath);
          }
          // Delete the data in the database
          try {
            wh.deleteDir(new Path(db.getLocationUri()), true);
          } catch (Exception e) {
            LOG.error("Failed to delete database directory: " + db.getLocationUri() +
                " " + e.getMessage());
          }
          // it is not a terrible thing even if the data is not deleted
        }
        for (MetaStoreEventListener listener : listeners) {
          listener.onDropDatabase(new DropDatabaseEvent(db, success, this));
        }
      }
    }

    /**
     * Returns a BEST GUESS as to whether or not other is a subdirectory of parent. It does not
     * take into account any intricacies of the underlying file system, which is assumed to be
     * HDFS. This should not return any false positives, but may return false negatives.
     *
     * @param parent
     * @param other
     * @return
     */
    private boolean isSubdirectory(Path parent, Path other) {
      return other.toString().startsWith(parent.toString().endsWith(Path.SEPARATOR) ?
          parent.toString() : parent.toString() + Path.SEPARATOR);
    }

    @Override
    public void drop_database(final String dbName, final boolean deleteData, final boolean cascade)
        throws NoSuchObjectException, InvalidOperationException, MetaException {

      startFunction("drop_database", ": " + dbName);
      if (DEFAULT_DATABASE_NAME.equalsIgnoreCase(dbName)) {
        endFunction("drop_database", false, null);
        throw new MetaException("Can not drop default database");
      }

      boolean success = false;
      Exception ex = null;
      try {
        drop_database_core(getMS(), dbName, deleteData, cascade);
        success = true;
      } catch (IOException e) {
        ex = e;
        throw new MetaException(e.getMessage());
      } catch (Exception e) {
        ex = e;
        if (e instanceof MetaException) {
          throw (MetaException) e;
        } else if (e instanceof InvalidOperationException) {
          throw (InvalidOperationException) e;
        } else if (e instanceof NoSuchObjectException) {
          throw (NoSuchObjectException) e;
        } else {
          throw newMetaException(e);
        }
      } finally {
        endFunction("drop_database", success, ex);
      }
    }

    @Override
    public List<String> get_databases(final String pattern) throws MetaException {
      startFunction("get_databases", ": " + pattern);

      List<String> ret = null;
      Exception ex = null;
      try {
        ret = getMS().getDatabases(pattern);
      } catch (Exception e) {
        ex = e;
        if (e instanceof MetaException) {
          throw (MetaException) e;
        } else {
          throw newMetaException(e);
        }
      } finally {
        endFunction("get_databases", ret != null, ex);
      }
      return ret;
    }

    @Override
    public List<String> get_all_databases() throws MetaException {
      startFunction("get_all_databases");

      List<String> ret = null;
      Exception ex = null;
      try {
        ret = getMS().getAllDatabases();
      } catch (Exception e) {
        ex = e;
        if (e instanceof MetaException) {
          throw (MetaException) e;
        } else {
          throw newMetaException(e);
        }
      } finally {
        endFunction("get_all_databases", ret != null, ex);
      }
      return ret;
    }

    private void create_type_core(final RawStore ms, final Type type)
        throws AlreadyExistsException, MetaException, InvalidObjectException {
      if (!MetaStoreUtils.validateName(type.getName())) {
        throw new InvalidObjectException("Invalid type name");
      }

      boolean success = false;
      try {
        ms.openTransaction();
        if (is_type_exists(ms, type.getName())) {
          throw new AlreadyExistsException("Type " + type.getName() + " already exists");
        }
        ms.createType(type);
        success = ms.commitTransaction();
      } finally {
        if (!success) {
          ms.rollbackTransaction();
        }
      }
    }

    @Override
    public boolean create_type(final Type type) throws AlreadyExistsException,
        MetaException, InvalidObjectException {
      startFunction("create_type", ": " + type.toString());
      boolean success = false;
      Exception ex = null;
      try {
        create_type_core(getMS(), type);
        success = true;
      } catch (Exception e) {
        ex = e;
        if (e instanceof MetaException) {
          throw (MetaException) e;
        } else if (e instanceof InvalidObjectException) {
          throw (InvalidObjectException) e;
        } else if (e instanceof AlreadyExistsException) {
          throw (AlreadyExistsException) e;
        } else {
          throw newMetaException(e);
        }
      } finally {
        endFunction("create_type", success, ex);
      }

      return success;
    }

    @Override
    public Type get_type(final String name) throws MetaException, NoSuchObjectException {
      startFunction("get_type", ": " + name);

      Type ret = null;
      Exception ex = null;
      try {
        ret = getMS().getType(name);
        if (null == ret) {
          throw new NoSuchObjectException("Type \"" + name + "\" not found.");
        }
      } catch (Exception e) {
        ex = e;
        if (e instanceof MetaException) {
          throw (MetaException) e;
        } else if (e instanceof NoSuchObjectException) {
          throw (NoSuchObjectException) e;
        } else {
          throw newMetaException(e);
        }
      } finally {
        endFunction("get_type", ret != null, ex);
      }
      return ret;
    }

    private boolean is_type_exists(RawStore ms, String typeName)
        throws MetaException {
      return (ms.getType(typeName) != null);
    }

    private void drop_type_core(final RawStore ms, String typeName)
        throws NoSuchObjectException, MetaException {
      boolean success = false;
      try {
        ms.openTransaction();
        // drop any partitions
        if (!is_type_exists(ms, typeName)) {
          throw new NoSuchObjectException(typeName + " doesn't exist");
        }
        if (!ms.dropType(typeName)) {
          throw new MetaException("Unable to drop type " + typeName);
        }
        success = ms.commitTransaction();
      } finally {
        if (!success) {
          ms.rollbackTransaction();
        }
      }
    }

    @Override
    public boolean drop_type(final String name) throws MetaException, NoSuchObjectException {
      startFunction("drop_type", ": " + name);

      boolean success = false;
      Exception ex = null;
      try {
        // TODO:pc validate that there are no types that refer to this
        success = getMS().dropType(name);
      } catch (Exception e) {
        ex = e;
        if (e instanceof MetaException) {
          throw (MetaException) e;
        } else if (e instanceof NoSuchObjectException) {
          throw (NoSuchObjectException) e;
        } else {
          throw newMetaException(e);
        }
      } finally {
        endFunction("drop_type", success, ex);
      }
      return success;
    }

    @Override
    public Map<String, Type> get_type_all(String name) throws MetaException {
      // TODO Auto-generated method stub
      startFunction("get_type_all", ": " + name);
      endFunction("get_type_all", false, null);
      throw new MetaException("Not yet implemented");
    }

    private void create_table_core(final RawStore ms, final Table tbl,
        final EnvironmentContext envContext)
        throws AlreadyExistsException, MetaException,
        InvalidObjectException, NoSuchObjectException {

      if (!MetaStoreUtils.validateName(tbl.getTableName())) {
        throw new InvalidObjectException(tbl.getTableName()
            + " is not a valid object name");
      }
      String validate = MetaStoreUtils.validateTblColumns(tbl.getSd().getCols());
      if (validate != null) {
        throw new InvalidObjectException("Invalid column " + validate);
      }
      if (tbl.getPartitionKeys() != null) {
        validate = MetaStoreUtils.validateTblColumns(tbl.getPartitionKeys());
        if (validate != null) {
          throw new InvalidObjectException("Invalid partition column " + validate);
        }
      }
      SkewedInfo skew = tbl.getSd().getSkewedInfo();
      if (skew != null) {
        validate = MetaStoreUtils.validateSkewedColNames(skew.getSkewedColNames());
        if (validate != null) {
          throw new InvalidObjectException("Invalid skew column " + validate);
        }
        validate = MetaStoreUtils.validateSkewedColNamesSubsetCol(
            skew.getSkewedColNames(), tbl.getSd().getCols());
        if (validate != null) {
          throw new InvalidObjectException("Invalid skew column " + validate);
        }
      }

      Path tblPath = null;
      boolean success = false, madeDir = false;
      try {
        firePreEvent(new PreCreateTableEvent(tbl, this));

        ms.openTransaction();

        Database db = ms.getDatabase(tbl.getDbName());
        if (db == null) {
          throw new NoSuchObjectException("The database " + tbl.getDbName() + " does not exist");
        }

        // get_table checks whether database exists, it should be moved here
        if (is_table_exists(ms, tbl.getDbName(), tbl.getTableName())) {
          throw new AlreadyExistsException("Table " + tbl.getTableName()
              + " already exists");
        }

        if (!TableType.VIRTUAL_VIEW.toString().equals(tbl.getTableType())) {
          if (tbl.getSd().getLocation() == null
              || tbl.getSd().getLocation().isEmpty()) {
            tblPath = wh.getTablePath(
                ms.getDatabase(tbl.getDbName()), tbl.getTableName());
          } else {
            if (!isExternal(tbl) && !MetaStoreUtils.isNonNativeTable(tbl)) {
              LOG.warn("Location: " + tbl.getSd().getLocation()
                  + " specified for non-external table:" + tbl.getTableName());
            }
            tblPath = wh.getDnsPath(new Path(tbl.getSd().getLocation()));
          }
          tbl.getSd().setLocation(tblPath.toString());
        }

        if (tblPath != null) {
          if (!wh.isDir(tblPath)) {
            if (!wh.mkdirs(tblPath, true)) {
              throw new MetaException(tblPath
                  + " is not a directory or unable to create one");
            }
            madeDir = true;
          }
        }
        if (HiveConf.getBoolVar(hiveConf, HiveConf.ConfVars.HIVESTATSAUTOGATHER) &&
            !MetaStoreUtils.isView(tbl)) {
          if (tbl.getPartitionKeysSize() == 0)  { // Unpartitioned table
            MetaStoreUtils.updateUnpartitionedTableStatsFast(db, tbl, wh, madeDir);
          } else { // Partitioned table with no partitions.
            MetaStoreUtils.updateUnpartitionedTableStatsFast(db, tbl, wh, true);
          }
        }

        // set create time
        long time = System.currentTimeMillis() / 1000;
        tbl.setCreateTime((int) time);
        if (tbl.getParameters() == null ||
            tbl.getParameters().get(hive_metastoreConstants.DDL_TIME) == null) {
          tbl.putToParameters(hive_metastoreConstants.DDL_TIME, Long.toString(time));
        }
        ms.createTable(tbl);
        success = ms.commitTransaction();

      } finally {
        if (!success) {
          ms.rollbackTransaction();
          if (madeDir) {
            wh.deleteDir(tblPath, true);
          }
        }
        for (MetaStoreEventListener listener : listeners) {
          CreateTableEvent createTableEvent =
              new CreateTableEvent(tbl, success, this);
          createTableEvent.setEnvironmentContext(envContext);
          listener.onCreateTable(createTableEvent);
        }
      }
    }

    @Override
    public void create_table(final Table tbl) throws AlreadyExistsException,
        MetaException, InvalidObjectException {
      create_table_with_environment_context(tbl, null);
    }

    @Override
    public void create_table_with_environment_context(final Table tbl,
        final EnvironmentContext envContext)
        throws AlreadyExistsException, MetaException, InvalidObjectException {
      startFunction("create_table", ": " + tbl.toString());
      boolean success = false;
      Exception ex = null;
      try {
        create_table_core(getMS(), tbl, envContext);
        success = true;
      } catch (NoSuchObjectException e) {
        ex = e;
        throw new InvalidObjectException(e.getMessage());
      } catch (Exception e) {
        ex = e;
        if (e instanceof MetaException) {
          throw (MetaException) e;
        } else if (e instanceof InvalidObjectException) {
          throw (InvalidObjectException) e;
        } else if (e instanceof AlreadyExistsException) {
          throw (AlreadyExistsException) e;
        } else {
          throw newMetaException(e);
        }
      } finally {
        endFunction("create_table", success, ex, tbl.getTableName());
      }
    }

    private boolean is_table_exists(RawStore ms, String dbname, String name)
        throws MetaException {
      return (ms.getTable(dbname, name) != null);
    }

    private boolean drop_table_core(final RawStore ms, final String dbname, final String name,
        final boolean deleteData, final EnvironmentContext envContext,
        final String indexName) throws NoSuchObjectException,
        MetaException, IOException, InvalidObjectException, InvalidInputException {
      boolean success = false;
      boolean isExternal = false;
      Path tblPath = null;
      List<Path> partPaths = null;
      Table tbl = null;
      boolean ifPurge = false;
      try {
        ms.openTransaction();
        // drop any partitions
        tbl = get_table_core(dbname, name);
        if (tbl == null) {
          throw new NoSuchObjectException(name + " doesn't exist");
        }
        if (tbl.getSd() == null) {
          throw new MetaException("Table metadata is corrupted");
        }
        ifPurge = isMustPurge(envContext, tbl);

        firePreEvent(new PreDropTableEvent(tbl, deleteData, this));

        boolean isIndexTable = isIndexTable(tbl);
        if (indexName == null && isIndexTable) {
          throw new RuntimeException(
              "The table " + name + " is an index table. Please do drop index instead.");
        }

        if (!isIndexTable) {
          try {
            List<Index> indexes = ms.getIndexes(dbname, name, Short.MAX_VALUE);
            while (indexes != null && indexes.size() > 0) {
              for (Index idx : indexes) {
                this.drop_index_by_name(dbname, name, idx.getIndexName(), true);
              }
              indexes = ms.getIndexes(dbname, name, Short.MAX_VALUE);
            }
          } catch (TException e) {
            throw new MetaException(e.getMessage());
          }
        }
        isExternal = isExternal(tbl);
        if (tbl.getSd().getLocation() != null) {
          tblPath = new Path(tbl.getSd().getLocation());
          if (!wh.isWritable(tblPath.getParent())) {
            String target = indexName == null ? "Table" : "Index table";
            throw new MetaException(target + " metadata not deleted since " +
                tblPath.getParent() + " is not writable by " +
                hiveConf.getUser());
          }
        }

        checkTrashPurgeCombination(tblPath, dbname + "." + name, ifPurge, deleteData && !isExternal);
        // Drop the partitions and get a list of locations which need to be deleted
        partPaths = dropPartitionsAndGetLocations(ms, dbname, name, tblPath,
            tbl.getPartitionKeys(), deleteData && !isExternal);

        if (!ms.dropTable(dbname, name)) {
          String tableName = dbname + "." + name;
          throw new MetaException(indexName == null ? "Unable to drop table " + tableName:
              "Unable to drop index table " + tableName + " for index " + indexName);
        }
        success = ms.commitTransaction();
      } finally {
        if (!success) {
          ms.rollbackTransaction();
        } else if (deleteData && !isExternal) {
          // Data needs deletion. Check if trash may be skipped.
          // Delete the data in the partitions which have other locations
          deletePartitionData(partPaths, ifPurge);
          // Delete the data in the table
          deleteTableData(tblPath, ifPurge);
          // ok even if the data is not deleted
        }
        for (MetaStoreEventListener listener : listeners) {
          DropTableEvent dropTableEvent = new DropTableEvent(tbl, success, deleteData, this);
          dropTableEvent.setEnvironmentContext(envContext);
          listener.onDropTable(dropTableEvent);
        }
      }
      return success;
    }

    /**
     * Will throw MetaException if combination of trash policy/purge can't be satisfied
     * @param pathToData path to data which may potentially be moved to trash
     * @param objectName db.table, or db.table.part
     * @param ifPurge if PURGE options is specified
     */
    private void checkTrashPurgeCombination(Path pathToData, String objectName, boolean ifPurge,
        boolean deleteData) throws MetaException {
      // There is no need to check TrashPurgeCombination in following cases since Purge/Trash
      // is not applicable:
      // a) deleteData is false -- drop an external table
      // b) pathToData is null -- a view
      // c) ifPurge is true -- force delete without Trash
      if (!deleteData || pathToData == null || ifPurge) {
        return;
      }

      boolean trashEnabled = false;
      try {
        trashEnabled = 0 < hiveConf.getFloat("fs.trash.interval", -1);
      } catch(NumberFormatException ex) {
	// nothing to do
      }

      if (trashEnabled) {
        try {
          HadoopShims.HdfsEncryptionShim shim =
            ShimLoader.getHadoopShims().createHdfsEncryptionShim(FileSystem.get(hiveConf), hiveConf);
          if (shim.isPathEncrypted(pathToData)) {
            throw new MetaException("Unable to drop " + objectName + " because it is in an encryption zone" +
              " and trash is enabled.  Use PURGE option to skip trash.");
          }
        } catch (IOException ex) {
          MetaException e = new MetaException(ex.getMessage());
          e.initCause(ex);
          throw e;
        }
      }
    }

    /**
     * Deletes the data in a table's location, if it fails logs an error
     *
     * @param tablePath
     */
    private void deleteTableData(Path tablePath) {
      deleteTableData(tablePath, false);
    }

    /**
     * Deletes the data in a table's location, if it fails logs an error
     *
     * @param tablePath
     * @param ifPurge completely purge the table (skipping trash) while removing
     *                data from warehouse
     */
    private void deleteTableData(Path tablePath, boolean ifPurge) {

      if (tablePath != null) {
        try {
          wh.deleteDir(tablePath, true, ifPurge);
        } catch (Exception e) {
          LOG.error("Failed to delete table directory: " + tablePath +
              " " + e.getMessage());
        }
      }
    }

    /**
     * Give a list of partitions' locations, tries to delete each one
     * and for each that fails logs an error.
     *
     * @param partPaths
     */
    private void deletePartitionData(List<Path> partPaths) {
      deletePartitionData(partPaths, false);
    }

    /**
    * Give a list of partitions' locations, tries to delete each one
    * and for each that fails logs an error.
    *
    * @param partPaths
    * @param ifPurge completely purge the partition (skipping trash) while
    *                removing data from warehouse
    */
    private void deletePartitionData(List<Path> partPaths, boolean ifPurge) {
      if (partPaths != null && !partPaths.isEmpty()) {
        for (Path partPath : partPaths) {
          try {
            wh.deleteDir(partPath, true, ifPurge);
          } catch (Exception e) {
            LOG.error("Failed to delete partition directory: " + partPath +
                " " + e.getMessage());
          }
        }
      }
    }

    /**
     * Retrieves the partitions specified by partitionKeys. If checkLocation, for locations of
     * partitions which may not be subdirectories of tablePath checks to make the locations are
     * writable.
     *
     * Drops the metadata for each partition.
     *
     * Provides a list of locations of partitions which may not be subdirectories of tablePath.
     *
     * @param ms
     * @param dbName
     * @param tableName
     * @param tablePath
     * @param partitionKeys
     * @param checkLocation
     * @return
     * @throws MetaException
     * @throws IOException
     * @throws InvalidInputException
     * @throws InvalidObjectException
     * @throws NoSuchObjectException
     */
    private List<Path> dropPartitionsAndGetLocations(RawStore ms, String dbName,
      String tableName, Path tablePath, List<FieldSchema> partitionKeys, boolean checkLocation)
      throws MetaException, IOException, NoSuchObjectException, InvalidObjectException,
      InvalidInputException {
      int partitionBatchSize = HiveConf.getIntVar(hiveConf,
          ConfVars.METASTORE_BATCH_RETRIEVE_MAX);
      Path tableDnsPath = null;
      if (tablePath != null) {
        tableDnsPath = wh.getDnsPath(tablePath);
      }
      List<Path> partPaths = new ArrayList<Path>();
      Table tbl = ms.getTable(dbName, tableName);

      // call dropPartition on each of the table's partitions to follow the
      // procedure for cleanly dropping partitions.
      while (true) {
        List<Partition> partsToDelete = ms.getPartitions(dbName, tableName, partitionBatchSize);
        if (partsToDelete == null || partsToDelete.isEmpty()) {
          break;
        }
        List<String> partNames = new ArrayList<String>();
        for (Partition part : partsToDelete) {
          if (checkLocation && part.getSd() != null &&
              part.getSd().getLocation() != null) {

            Path partPath = wh.getDnsPath(new Path(part.getSd().getLocation()));
            if (tableDnsPath == null ||
                (partPath != null && !isSubdirectory(tableDnsPath, partPath))) {
              if (!wh.isWritable(partPath.getParent())) {
                throw new MetaException("Table metadata not deleted since the partition " +
                    Warehouse.makePartName(partitionKeys, part.getValues()) +
                    " has parent location " + partPath.getParent() + " which is not writable " +
                    "by " + hiveConf.getUser());
              }
              partPaths.add(partPath);
            }
          }
          partNames.add(Warehouse.makePartName(tbl.getPartitionKeys(), part.getValues()));
        }
        for (MetaStoreEventListener listener : listeners) {
          //No drop part listener events fired for public listeners historically, for drop table case.
          //Limiting to internal listeners for now, to avoid unexpected calls for public listeners.
          if (listener instanceof HMSMetricsListener) {
            for (Partition part : partsToDelete) {
              listener.onDropPartition(null);
            }
          }
        }
        ms.dropPartitions(dbName, tableName, partNames);
      }

      return partPaths;
    }

    @Override
    public void drop_table(final String dbname, final String name, final boolean deleteData)
        throws NoSuchObjectException, MetaException {
      drop_table_with_environment_context(dbname, name, deleteData, null);
    }

    @Override
    public void drop_table_with_environment_context(final String dbname, final String name,
        final boolean deleteData, final EnvironmentContext envContext)
        throws NoSuchObjectException, MetaException {
      startTableFunction("drop_table", dbname, name);

      boolean success = false;
      Exception ex = null;
      try {
        success = drop_table_core(getMS(), dbname, name, deleteData, envContext, null);
      } catch (IOException e) {
        ex = e;
        throw new MetaException(e.getMessage());
      } catch (Exception e) {
        ex = e;
        if (e instanceof MetaException) {
          throw (MetaException) e;
        } else if (e instanceof NoSuchObjectException) {
          throw (NoSuchObjectException) e;
        } else {
          throw newMetaException(e);
        }
      } finally {
        endFunction("drop_table", success, ex, name);
      }

    }

    /**
     * Is this an external table?
     *
     * @param table
     *          Check if this table is external.
     * @return True if the table is external, otherwise false.
     */
    private boolean isExternal(Table table) {
      return MetaStoreUtils.isExternalTable(table);
    }

    private boolean isIndexTable(Table table) {
      return MetaStoreUtils.isIndexTable(table);
    }

    @Override
    public Table get_table(final String dbname, final String name) throws MetaException,
        NoSuchObjectException {
      Table t = null;
      startTableFunction("get_table", dbname, name);
      Exception ex = null;
      try {
        t = get_table_core(dbname, name);
        firePreEvent(new PreReadTableEvent(t, this));
      } catch (MetaException e) {
        ex = e;
        throw e;
      } catch (NoSuchObjectException e) {
        ex = e;
        throw e;
      } finally {
        endFunction("get_table", t != null, ex, name);
      }
      return t;
    }

    @Override
    public List<TableMeta> get_table_meta(String dbnames, String tblNames, List<String> tblTypes)
        throws MetaException, NoSuchObjectException {
      List<TableMeta> t = null;
      startTableFunction("get_table_metas", dbnames, tblNames);
      Exception ex = null;
      try {
        t = getMS().getTableMeta(dbnames, tblNames, tblTypes);
      } catch (Exception e) {
        ex = e;
        throw newMetaException(e);
      } finally {
        endFunction("get_table_metas", t != null, ex);
      }
      return t;
    }

    /**
     * Equivalent of get_table, but does not log audits and fire pre-event listener.
     * Meant to be used for calls made by other hive classes, that are not using the
     * thrift interface.
     * @param dbname
     * @param name
     * @return Table object
     * @throws MetaException
     * @throws NoSuchObjectException
     */
    public Table get_table_core(final String dbname, final String name) throws MetaException,
        NoSuchObjectException {
      Table t;
      try {
        t = getMS().getTable(dbname, name);
        if (t == null) {
          throw new NoSuchObjectException(dbname + "." + name
              + " table not found");
        }
      } catch (Exception e) {
        if (e instanceof MetaException) {
          throw (MetaException) e;
        } else if (e instanceof NoSuchObjectException) {
          throw (NoSuchObjectException) e;
        } else {
          throw newMetaException(e);
        }
      }
      return t;
    }

    /**
     * Gets multiple tables from the hive metastore.
     *
     * @param dbName
     *          The name of the database in which the tables reside
     * @param tableNames
     *          The names of the tables to get.
     *
     * @return A list of tables whose names are in the the list "names" and
     *         are retrievable from the database specified by "dbnames."
     *         There is no guarantee of the order of the returned tables.
     *         If there are duplicate names, only one instance of the table will be returned.
     * @throws MetaException
     * @throws InvalidOperationException
     * @throws UnknownDBException
     */
    @Override
    public List<Table> get_table_objects_by_name(final String dbName, final List<String> tableNames)
        throws MetaException, InvalidOperationException, UnknownDBException {
      List<Table> tables = new ArrayList<Table>();
      startMultiTableFunction("get_multi_table", dbName, tableNames);
      Exception ex = null;
      int tableBatchSize = HiveConf.getIntVar(hiveConf,
          ConfVars.METASTORE_BATCH_RETRIEVE_MAX);

      try {
        if (dbName == null || dbName.isEmpty()) {
          throw new UnknownDBException("DB name is null or empty");
        }
        if (tableNames == null)
        {
          throw new InvalidOperationException(dbName + " cannot find null tables");
        }

        // The list of table names could contain duplicates. RawStore.getTableObjectsByName()
        // only guarantees returning no duplicate table objects in one batch. If we need
        // to break into multiple batches, remove duplicates first.
        List<String> distinctTableNames = tableNames;
        if (distinctTableNames.size() > tableBatchSize) {
          List<String> lowercaseTableNames = new ArrayList<String>();
          for (String tableName : tableNames) {
            lowercaseTableNames.add(HiveStringUtils.normalizeIdentifier(tableName));
          }
          distinctTableNames = new ArrayList<String>(new HashSet<String>(lowercaseTableNames));
        }

        RawStore ms = getMS();
        int startIndex = 0;
        // Retrieve the tables from the metastore in batches. Some databases like
        // Oracle cannot have over 1000 expressions in a in-list
        while (startIndex < distinctTableNames.size()) {
          int endIndex = Math.min(startIndex + tableBatchSize, distinctTableNames.size());
          tables.addAll(ms.getTableObjectsByName(dbName, distinctTableNames.subList(startIndex, endIndex)));
          startIndex = endIndex;
        }
      } catch (Exception e) {
        ex = e;
        if (e instanceof MetaException) {
          throw (MetaException) e;
        } else if (e instanceof InvalidOperationException) {
          throw (InvalidOperationException) e;
        } else if (e instanceof UnknownDBException) {
          throw (UnknownDBException) e;
        } else {
          throw newMetaException(e);
        }
      } finally {
        endFunction("get_multi_table", tables != null, ex, join(tableNames, ","));
      }
      return tables;
    }

    @Override
    public List<String> get_table_names_by_filter(
        final String dbName, final String filter, final short maxTables)
        throws MetaException, InvalidOperationException, UnknownDBException {
      List<String> tables = null;
      startFunction("get_table_names_by_filter", ": db = " + dbName + ", filter = " + filter);
      Exception ex = null;
      try {
        if (dbName == null || dbName.isEmpty()) {
          throw new UnknownDBException("DB name is null or empty");
        }
        if (filter == null) {
          throw new InvalidOperationException(filter + " cannot apply null filter");
        }
        tables = getMS().listTableNamesByFilter(dbName, filter, maxTables);
      } catch (Exception e) {
        ex = e;
        if (e instanceof MetaException) {
          throw (MetaException) e;
        } else if (e instanceof InvalidOperationException) {
          throw (InvalidOperationException) e;
        } else if (e instanceof UnknownDBException) {
          throw (UnknownDBException) e;
        } else {
          throw newMetaException(e);
        }
      } finally {
        endFunction("get_table_names_by_filter", tables != null, ex, join(tables, ","));
      }
      return tables;
    }

    private Partition append_partition_common(RawStore ms, String dbName, String tableName,
        List<String> part_vals, EnvironmentContext envContext) throws InvalidObjectException,
        AlreadyExistsException, MetaException {

      Partition part = new Partition();
      boolean success = false, madeDir = false;
      Path partLocation = null;
      Table tbl = null;
      try {
        ms.openTransaction();
        part.setDbName(dbName);
        part.setTableName(tableName);
        part.setValues(part_vals);

        MetaStoreUtils.validatePartitionNameCharacters(part_vals, partitionValidationPattern);

        tbl = ms.getTable(part.getDbName(), part.getTableName());
        if (tbl == null) {
          throw new InvalidObjectException(
              "Unable to add partition because table or database do not exist");
        }
        if (tbl.getSd().getLocation() == null) {
          throw new MetaException(
              "Cannot append a partition to a view");
        }

        firePreEvent(new PreAddPartitionEvent(tbl, part, this));

        part.setSd(tbl.getSd());
        partLocation = new Path(tbl.getSd().getLocation(), Warehouse
            .makePartName(tbl.getPartitionKeys(), part_vals));
        part.getSd().setLocation(partLocation.toString());

        Partition old_part = null;
        try {
          old_part = ms.getPartition(part.getDbName(), part
              .getTableName(), part.getValues());
        } catch (NoSuchObjectException e) {
          // this means there is no existing partition
          old_part = null;
        }
        if (old_part != null) {
          throw new AlreadyExistsException("Partition already exists:" + part);
        }

        if (!wh.isDir(partLocation)) {
          if (!wh.mkdirs(partLocation, true)) {
            throw new MetaException(partLocation
                + " is not a directory or unable to create one");
          }
          madeDir = true;
        }

        // set create time
        long time = System.currentTimeMillis() / 1000;
        part.setCreateTime((int) time);
        part.putToParameters(hive_metastoreConstants.DDL_TIME, Long.toString(time));

        if (HiveConf.getBoolVar(hiveConf, HiveConf.ConfVars.HIVESTATSAUTOGATHER) &&
            !MetaStoreUtils.isView(tbl)) {
          MetaStoreUtils.updatePartitionStatsFast(part, wh, madeDir);
        }

        success = ms.addPartition(part);
        if (success) {
          success = ms.commitTransaction();
        }
      } finally {
        if (!success) {
          ms.rollbackTransaction();
          if (madeDir) {
            wh.deleteDir(partLocation, true);
          }
        }

        for (MetaStoreEventListener listener : listeners) {
          AddPartitionEvent addPartitionEvent =
              new AddPartitionEvent(tbl, part, success, this);
          addPartitionEvent.setEnvironmentContext(envContext);
          listener.onAddPartition(addPartitionEvent);
        }
      }
      return part;
    }

    private void firePreEvent(PreEventContext event) throws MetaException {
      for (MetaStorePreEventListener listener : preListeners) {
        try {
          listener.onEvent(event);
        } catch (NoSuchObjectException e) {
          throw new MetaException(e.getMessage());
        } catch (InvalidOperationException e) {
          throw new MetaException(e.getMessage());
        }
      }
    }

    @Override
    public Partition append_partition(final String dbName, final String tableName,
        final List<String> part_vals) throws InvalidObjectException,
        AlreadyExistsException, MetaException {
      return append_partition_with_environment_context(dbName, tableName, part_vals, null);
    }

    @Override
    public Partition append_partition_with_environment_context(final String dbName,
        final String tableName, final List<String> part_vals, final EnvironmentContext envContext)
        throws InvalidObjectException, AlreadyExistsException, MetaException {
      startPartitionFunction("append_partition", dbName, tableName, part_vals);
      if (LOG.isDebugEnabled()) {
        for (String part : part_vals) {
          LOG.debug(part);
        }
      }

      Partition ret = null;
      Exception ex = null;
      try {
        ret = append_partition_common(getMS(), dbName, tableName, part_vals, envContext);
      } catch (Exception e) {
        ex = e;
        if (e instanceof MetaException) {
          throw (MetaException) e;
        } else if (e instanceof InvalidObjectException) {
          throw (InvalidObjectException) e;
        } else if (e instanceof AlreadyExistsException) {
          throw (AlreadyExistsException) e;
        } else {
          throw newMetaException(e);
        }
      } finally {
        endFunction("append_partition", ret != null, ex, tableName);
      }
      return ret;
    }

    private static class PartValEqWrapper {
      Partition partition;

      public PartValEqWrapper(Partition partition) {
        this.partition = partition;
      }

      @Override
      public int hashCode() {
        return partition.isSetValues() ? partition.getValues().hashCode() : 0;
      }

      @Override
      public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || !(obj instanceof PartValEqWrapper)) return false;
        Partition p1 = this.partition, p2 = ((PartValEqWrapper)obj).partition;
        if (!p1.isSetValues() || !p2.isSetValues()) return p1.isSetValues() == p2.isSetValues();
        if (p1.getValues().size() != p2.getValues().size()) return false;
        for (int i = 0; i < p1.getValues().size(); ++i) {
          String v1 = p1.getValues().get(i), v2 = p2.getValues().get(i);
          if ((v1 == null && v2 != null) || !v1.equals(v2)) return false;
        }
        return true;
      }
    }

    private static class PartValEqWrapperLite {
      List<String> values;
      String location;

      public PartValEqWrapperLite(Partition partition) {
        this.values = partition.isSetValues()? partition.getValues() : null;
        this.location = partition.getSd().getLocation();
      }

      @Override
      public int hashCode() {
        return values == null ? 0 : values.hashCode();
      }

      @Override
      public boolean equals(Object obj) {
        if (this == obj) {
          return true;
        }
        if (obj == null || !(obj instanceof PartValEqWrapperLite)) {
          return false;
        }

        List<String> lhsValues = this.values;
        List<String> rhsValues = ((PartValEqWrapperLite)obj).values;

        if (lhsValues == null || rhsValues == null)
          return lhsValues == rhsValues;

        if (lhsValues.size() != rhsValues.size())
          return false;

        for (int i=0; i<lhsValues.size(); ++i) {
          String lhsValue = lhsValues.get(i);
          String rhsValue = rhsValues.get(i);

          if ((lhsValue == null && rhsValue != null)
              || (lhsValue != null && !lhsValue.equals(rhsValue))) {
            return false;
          }
        }

        return true;
      }
    }

    private List<Partition> add_partitions_core(
        RawStore ms, String dbName, String tblName, List<Partition> parts, boolean ifNotExists)
            throws MetaException, InvalidObjectException, AlreadyExistsException, TException {
      logInfo("add_partitions");
      boolean success = false;
      // Ensures that the list doesn't have dups, and keeps track of directories we have created.
      Map<PartValEqWrapper, Boolean> addedPartitions = new HashMap<PartValEqWrapper, Boolean>();
      List<Partition> result = new ArrayList<Partition>();
      List<Partition> existingParts = null;
      Table tbl = null;
      try {
        ms.openTransaction();
        tbl = ms.getTable(dbName, tblName);
        if (tbl == null) {
          throw new InvalidObjectException("Unable to add partitions because "
              + "database or table " + dbName + "." + tblName + " does not exist");
        }

        if (!parts.isEmpty()) {
          firePreEvent(new PreAddPartitionEvent(tbl, parts, this));
        }

        for (Partition part : parts) {
          if (!part.getTableName().equals(tblName) || !part.getDbName().equals(dbName)) {
            throw new MetaException("Partition does not belong to target table "
                + dbName + "." + tblName + ": " + part);
          }
          boolean shouldAdd = startAddPartition(ms, part, ifNotExists);
          if (!shouldAdd) {
            if (existingParts == null) {
              existingParts = new ArrayList<Partition>();
            }
            existingParts.add(part);
            LOG.info("Not adding partition " + part + " as it already exists");
            continue;
          }
          boolean madeDir = createLocationForAddedPartition(tbl, part);
          if (addedPartitions.put(new PartValEqWrapper(part), madeDir) != null) {
            // Technically, for ifNotExists case, we could insert one and discard the other
            // because the first one now "exists", but it seems better to report the problem
            // upstream as such a command doesn't make sense.
            throw new MetaException("Duplicate partitions in the list: " + part);
          }
          initializeAddedPartition(tbl, part, madeDir);
          result.add(part);
        }
        if (!result.isEmpty()) {
          success = ms.addPartitions(dbName, tblName, result);
        } else {
          success = true;
        }
        success = success && ms.commitTransaction();
      } finally {
        if (!success) {
          ms.rollbackTransaction();
          for (Entry<PartValEqWrapper, Boolean> e : addedPartitions.entrySet()) {
            if (e.getValue()) {
              wh.deleteDir(new Path(e.getKey().partition.getSd().getLocation()), true);
              // we just created this directory - it's not a case of pre-creation, so we nuke
            }
          }
          fireMetaStoreAddPartitionEvent(tbl, parts, null, false);
        } else {
          fireMetaStoreAddPartitionEvent(tbl, result, null, true);
          if (existingParts != null) {
            // The request has succeeded but we failed to add these partitions.
            fireMetaStoreAddPartitionEvent(tbl, existingParts, null, false);
          }
        }
      }
      return result;
    }

    @Override
    public AddPartitionsResult add_partitions_req(AddPartitionsRequest request)
        throws InvalidObjectException, AlreadyExistsException, MetaException, TException {
      AddPartitionsResult result = new AddPartitionsResult();
      if (request.getParts().isEmpty()) {
        return result;
      }
      try {
        List<Partition> parts = add_partitions_core(getMS(), request.getDbName(),
            request.getTblName(), request.getParts(), request.isIfNotExists());
        if (request.isNeedResult()) {
          result.setPartitions(parts);
        }
      } catch (TException te) {
        throw te;
      } catch (Exception e) {
        throw newMetaException(e);
      }
      return result;
    }

    @Override
    public int add_partitions(final List<Partition> parts) throws MetaException,
        InvalidObjectException, AlreadyExistsException {
      startFunction("add_partition");
      if (parts.size() == 0) {
        return 0;
      }

      Integer ret = null;
      Exception ex = null;
      try {
        // Old API assumed all partitions belong to the same table; keep the same assumption
        ret = add_partitions_core(getMS(), parts.get(0).getDbName(),
            parts.get(0).getTableName(), parts, false).size();
        assert ret == parts.size();
      } catch (Exception e) {
        ex = e;
        if (e instanceof MetaException) {
          throw (MetaException) e;
        } else if (e instanceof InvalidObjectException) {
          throw (InvalidObjectException) e;
        } else if (e instanceof AlreadyExistsException) {
          throw (AlreadyExistsException) e;
        } else {
          throw newMetaException(e);
        }
      } finally {
        String tableName = parts.get(0).getTableName();
        endFunction("add_partition", ret != null, ex, tableName);
      }
      return ret;
    }

    @Override
    public int add_partitions_pspec(final List<PartitionSpec> partSpecs)
        throws TException {
      logInfo("add_partitions_pspec");

      if (partSpecs.isEmpty()) {
        return 0;
      }

      String dbName = partSpecs.get(0).getDbName();
      String tableName = partSpecs.get(0).getTableName();

      return add_partitions_pspec_core(getMS(), dbName, tableName, partSpecs, false);
    }

    private int add_partitions_pspec_core(
        RawStore ms, String dbName, String tblName, List<PartitionSpec> partSpecs, boolean ifNotExists)
        throws TException {
      boolean success = false;
      // Ensures that the list doesn't have dups, and keeps track of directories we have created.
      Map<PartValEqWrapperLite, Boolean> addedPartitions = new HashMap<PartValEqWrapperLite, Boolean>();
      PartitionSpecProxy partitionSpecProxy = PartitionSpecProxy.Factory.get(partSpecs);
      PartitionSpecProxy.PartitionIterator partitionIterator = partitionSpecProxy.getPartitionIterator();
      Table tbl = null;
      try {
        ms.openTransaction();
        tbl = ms.getTable(dbName, tblName);
        if (tbl == null) {
          throw new InvalidObjectException("Unable to add partitions because "
              + "database or table " + dbName + "." + tblName + " does not exist");
        }

        firePreEvent(new PreAddPartitionEvent(tbl, partitionSpecProxy, this));

        int nPartitions = 0;
        while(partitionIterator.hasNext()) {

          Partition part = partitionIterator.getCurrent();

          if (!part.getTableName().equals(tblName) || !part.getDbName().equals(dbName)) {
            throw new MetaException("Partition does not belong to target table "
                + dbName + "." + tblName + ": " + part);
          }
          boolean shouldAdd = startAddPartition(ms, part, ifNotExists);
          if (!shouldAdd) {
            LOG.info("Not adding partition " + part + " as it already exists");
            continue;
          }
          boolean madeDir = createLocationForAddedPartition(tbl, part);
          if (addedPartitions.put(new PartValEqWrapperLite(part), madeDir) != null) {
            // Technically, for ifNotExists case, we could insert one and discard the other
            // because the first one now "exists", but it seems better to report the problem
            // upstream as such a command doesn't make sense.
            throw new MetaException("Duplicate partitions in the list: " + part);
          }
          initializeAddedPartition(tbl, partitionIterator, madeDir);

          ++nPartitions;
          partitionIterator.next();
        }

        success = ms.addPartitions(dbName, tblName, partitionSpecProxy, ifNotExists)
               && ms.commitTransaction();

        return nPartitions;
      } finally {
        if (!success) {
          ms.rollbackTransaction();
          for (Entry<PartValEqWrapperLite, Boolean> e : addedPartitions.entrySet()) {
            if (e.getValue()) {
              wh.deleteDir(new Path(e.getKey().location), true);
              // we just created this directory - it's not a case of pre-creation, so we nuke
            }
          }
        }
        fireMetaStoreAddPartitionEvent(tbl, partitionSpecProxy, null, true);
      }
    }

    private boolean startAddPartition(
        RawStore ms, Partition part, boolean ifNotExists) throws MetaException, TException {
      MetaStoreUtils.validatePartitionNameCharacters(part.getValues(),
          partitionValidationPattern);
      boolean doesExist = ms.doesPartitionExist(
          part.getDbName(), part.getTableName(), part.getValues());
      if (doesExist && !ifNotExists) {
        throw new AlreadyExistsException("Partition already exists: " + part);
      }
      return !doesExist;
    }

    /**
     * Handles the location for a partition being created.
     * @param tbl Table.
     * @param part Partition.
     * @return Whether the partition SD location is set to a newly created directory.
     */
    private boolean createLocationForAddedPartition(
        final Table tbl, final Partition part) throws MetaException {
      Path partLocation = null;
      String partLocationStr = null;
      if (part.getSd() != null) {
        partLocationStr = part.getSd().getLocation();
      }

      if (partLocationStr == null || partLocationStr.isEmpty()) {
        // set default location if not specified and this is
        // a physical table partition (not a view)
        if (tbl.getSd().getLocation() != null) {
          partLocation = new Path(tbl.getSd().getLocation(), Warehouse
              .makePartName(tbl.getPartitionKeys(), part.getValues()));
        }
      } else {
        if (tbl.getSd().getLocation() == null) {
          throw new MetaException("Cannot specify location for a view partition");
        }
        partLocation = wh.getDnsPath(new Path(partLocationStr));
      }

      boolean result = false;
      if (partLocation != null) {
        part.getSd().setLocation(partLocation.toString());

        // Check to see if the directory already exists before calling
        // mkdirs() because if the file system is read-only, mkdirs will
        // throw an exception even if the directory already exists.
        if (!wh.isDir(partLocation)) {
          if (!wh.mkdirs(partLocation, true)) {
            throw new MetaException(partLocation
                + " is not a directory or unable to create one");
          }
          result = true;
        }
      }
      return result;
    }

    private void initializeAddedPartition(
        final Table tbl, final Partition part, boolean madeDir) throws MetaException {
      initializeAddedPartition(tbl, new PartitionSpecProxy.SimplePartitionWrapperIterator(part), madeDir);
    }

    private void initializeAddedPartition(
        final Table tbl, final PartitionSpecProxy.PartitionIterator part, boolean madeDir) throws MetaException {
      if (HiveConf.getBoolVar(hiveConf, HiveConf.ConfVars.HIVESTATSAUTOGATHER) &&
          !MetaStoreUtils.isView(tbl)) {
        MetaStoreUtils.updatePartitionStatsFast(part, wh, madeDir, false);
      }

      // set create time
      long time = System.currentTimeMillis() / 1000;
      part.setCreateTime((int) time);
      if (part.getParameters() == null ||
          part.getParameters().get(hive_metastoreConstants.DDL_TIME) == null) {
        part.putToParameters(hive_metastoreConstants.DDL_TIME, Long.toString(time));
      }

      // Inherit table properties into partition properties.
      Map<String, String> tblParams = tbl.getParameters();
      String inheritProps = hiveConf.getVar(ConfVars.METASTORE_PART_INHERIT_TBL_PROPS).trim();
      // Default value is empty string in which case no properties will be inherited.
      // * implies all properties needs to be inherited
      Set<String> inheritKeys = new HashSet<String>(Arrays.asList(inheritProps.split(",")));
      if (inheritKeys.contains("*")) {
        inheritKeys = tblParams.keySet();
      }

      for (String key : inheritKeys) {
        String paramVal = tblParams.get(key);
        if (null != paramVal) { // add the property only if it exists in table properties
          part.putToParameters(key, paramVal);
        }
      }
    }

    private Partition add_partition_core(final RawStore ms,
        final Partition part, final EnvironmentContext envContext)
        throws InvalidObjectException, AlreadyExistsException, MetaException, TException {
      boolean success = false;
      Table tbl = null;
      try {
        ms.openTransaction();
        tbl = ms.getTable(part.getDbName(), part.getTableName());
        if (tbl == null) {
          throw new InvalidObjectException(
              "Unable to add partition because table or database do not exist");
        }

        firePreEvent(new PreAddPartitionEvent(tbl, part, this));

        boolean shouldAdd = startAddPartition(ms, part, false);
        assert shouldAdd; // start would throw if it already existed here
        boolean madeDir = createLocationForAddedPartition(tbl, part);
        try {
          initializeAddedPartition(tbl, part, madeDir);
          success = ms.addPartition(part);
        } finally {
          if (!success && madeDir) {
            wh.deleteDir(new Path(part.getSd().getLocation()), true);
          }
        }
        // we proceed only if we'd actually succeeded anyway, otherwise,
        // we'd have thrown an exception
        success = success && ms.commitTransaction();
      } finally {
        if (!success) {
          ms.rollbackTransaction();
        }
        fireMetaStoreAddPartitionEvent(tbl, Arrays.asList(part), envContext, success);
      }
      return part;
    }

    private void fireMetaStoreAddPartitionEvent(final Table tbl,
        final List<Partition> parts, final EnvironmentContext envContext, boolean success)
          throws MetaException {
      if (tbl != null && parts != null && !parts.isEmpty()) {
        AddPartitionEvent addPartitionEvent =
            new AddPartitionEvent(tbl, parts, success, this);
        addPartitionEvent.setEnvironmentContext(envContext);

        for (MetaStoreEventListener listener : listeners) {
          listener.onAddPartition(addPartitionEvent);
        }
      }
    }

    private void fireMetaStoreAddPartitionEvent(final Table tbl,
        final PartitionSpecProxy partitionSpec, final EnvironmentContext envContext, boolean success)
          throws MetaException {
      if (tbl != null && partitionSpec != null) {
        AddPartitionEvent addPartitionEvent =
            new AddPartitionEvent(tbl, partitionSpec, success, this);
        addPartitionEvent.setEnvironmentContext(envContext);

        for (MetaStoreEventListener listener : listeners) {
          listener.onAddPartition(addPartitionEvent);
        }
      }
    }

    @Override
    public Partition add_partition(final Partition part)
        throws InvalidObjectException, AlreadyExistsException, MetaException {
      return add_partition_with_environment_context(part, null);
    }

    @Override
    public Partition add_partition_with_environment_context(
        final Partition part, EnvironmentContext envContext)
        throws InvalidObjectException, AlreadyExistsException,
        MetaException {
      startTableFunction("add_partition",
          part.getDbName(), part.getTableName());
      Partition ret = null;
      Exception ex = null;
      try {
        ret = add_partition_core(getMS(), part, envContext);
      } catch (Exception e) {
        ex = e;
        if (e instanceof MetaException) {
          throw (MetaException) e;
        } else if (e instanceof InvalidObjectException) {
          throw (InvalidObjectException) e;
        } else if (e instanceof AlreadyExistsException) {
          throw (AlreadyExistsException) e;
        } else {
          throw newMetaException(e);
        }
      } finally {
        endFunction("add_partition", ret != null, ex, part != null ?  part.getTableName(): null);
      }
      return ret;
    }

    @Override
    public Partition exchange_partition(Map<String, String> partitionSpecs,
        String sourceDbName, String sourceTableName, String destDbName,
        String destTableName) throws MetaException, NoSuchObjectException,
        InvalidObjectException, InvalidInputException, TException {
      boolean success = false;
      boolean pathCreated = false;
      RawStore ms = getMS();
      ms.openTransaction();
      Table destinationTable = ms.getTable(destDbName, destTableName);
      Table sourceTable = ms.getTable(sourceDbName, sourceTableName);
      List<String> partVals = MetaStoreUtils.getPvals(sourceTable.getPartitionKeys(),
          partitionSpecs);
      List<String> partValsPresent = new ArrayList<String> ();
      List<FieldSchema> partitionKeysPresent = new ArrayList<FieldSchema> ();
      int i = 0;
      for (FieldSchema fs: sourceTable.getPartitionKeys()) {
        String partVal = partVals.get(i);
        if (partVal != null && !partVal.equals("")) {
          partValsPresent.add(partVal);
          partitionKeysPresent.add(fs);
        }
        i++;
      }
      List<Partition> partitionsToExchange = get_partitions_ps(sourceDbName, sourceTableName,
          partVals, (short)-1);
      boolean sameColumns = MetaStoreUtils.compareFieldColumns(
          sourceTable.getSd().getCols(), destinationTable.getSd().getCols());
      boolean samePartitions = MetaStoreUtils.compareFieldColumns(
          sourceTable.getPartitionKeys(), destinationTable.getPartitionKeys());
      if (!sameColumns || !samePartitions) {
        throw new MetaException("The tables have different schemas." +
            " Their partitions cannot be exchanged.");
      }
      Path sourcePath = new Path(sourceTable.getSd().getLocation(),
          Warehouse.makePartName(partitionKeysPresent, partValsPresent));
      Path destPath = new Path(destinationTable.getSd().getLocation(),
          Warehouse.makePartName(partitionKeysPresent, partValsPresent));
      try {
        for (Partition partition: partitionsToExchange) {
          Partition destPartition = new Partition(partition);
          destPartition.setDbName(destDbName);
          destPartition.setTableName(destinationTable.getTableName());
          Path destPartitionPath = new Path(destinationTable.getSd().getLocation(),
              Warehouse.makePartName(destinationTable.getPartitionKeys(), partition.getValues()));
          destPartition.getSd().setLocation(destPartitionPath.toString());
          ms.addPartition(destPartition);
          ms.dropPartition(partition.getDbName(), sourceTable.getTableName(),
            partition.getValues());
        }
        Path destParentPath = destPath.getParent();
        if (!wh.isDir(destParentPath)) {
          if (!wh.mkdirs(destParentPath, true)) {
              throw new MetaException("Unable to create path " + destParentPath);
          }
        }
        /**
         * TODO: Use the hard link feature of hdfs
         * once https://issues.apache.org/jira/browse/HDFS-3370 is done
         */
        pathCreated = wh.renameDir(sourcePath, destPath);
        success = ms.commitTransaction();
      } finally {
        if (!success || !pathCreated) {
          ms.rollbackTransaction();
          if (pathCreated) {
            wh.renameDir(destPath, sourcePath);
          }
        }
      }
      return new Partition();
    }

    private boolean drop_partition_common(RawStore ms, String db_name, String tbl_name,
      List<String> part_vals, final boolean deleteData, final EnvironmentContext envContext)
      throws MetaException, NoSuchObjectException, IOException, InvalidObjectException,
      InvalidInputException {
      boolean success = false;
      Path partPath = null;
      Table tbl = null;
      Partition part = null;
      boolean isArchived = false;
      Path archiveParentDir = null;
      boolean mustPurge = false;
      boolean isExternalTbl = false;

      try {
        ms.openTransaction();
        part = ms.getPartition(db_name, tbl_name, part_vals);
        tbl = get_table_core(db_name, tbl_name);
        isExternalTbl = isExternal(tbl);
        firePreEvent(new PreDropPartitionEvent(tbl, part, deleteData, this));
        mustPurge = isMustPurge(envContext, tbl);

        if (part == null) {
          throw new NoSuchObjectException("Partition doesn't exist. "
              + part_vals);
        }

        isArchived = MetaStoreUtils.isArchived(part);
        if (isArchived) {
          archiveParentDir = MetaStoreUtils.getOriginalLocation(part);
          verifyIsWritablePath(archiveParentDir);
          checkTrashPurgeCombination(archiveParentDir, db_name + "." + tbl_name + "." + part_vals,
              mustPurge, deleteData && !isExternalTbl);
        }

        if ((part.getSd() != null) && (part.getSd().getLocation() != null)) {
          partPath = new Path(part.getSd().getLocation());
          verifyIsWritablePath(partPath);
          checkTrashPurgeCombination(partPath, db_name + "." + tbl_name + "." + part_vals,
              mustPurge, deleteData && !isExternalTbl);
        }

        if (!ms.dropPartition(db_name, tbl_name, part_vals)) {
          throw new MetaException("Unable to drop partition");
        }
        success = ms.commitTransaction();
      } finally {
        if (!success) {
          ms.rollbackTransaction();
        } else if (deleteData && ((partPath != null) || (archiveParentDir != null))) {
          if (!isExternalTbl) {
            if (mustPurge) {
              LOG.info("dropPartition() will purge " + partPath + " directly, skipping trash.");
            }
            else {
              LOG.info("dropPartition() will move " + partPath + " to trash-directory.");
            }
            // Archived partitions have har:/to_har_file as their location.
            // The original directory was saved in params
            if (isArchived) {
              assert (archiveParentDir != null);
              wh.deleteDir(archiveParentDir, true, mustPurge);
            } else {
              assert (partPath != null);
              wh.deleteDir(partPath, true, mustPurge);
              deleteParentRecursive(partPath.getParent(), part_vals.size() - 1, mustPurge);
            }
            // ok even if the data is not deleted
          }
        }
        for (MetaStoreEventListener listener : listeners) {
          DropPartitionEvent dropPartitionEvent =
            new DropPartitionEvent(tbl, part, success, deleteData, this);
          dropPartitionEvent.setEnvironmentContext(envContext);
          listener.onDropPartition(dropPartitionEvent);
        }
      }
      return true;
    }

    private static boolean isMustPurge(EnvironmentContext envContext, Table tbl) {
      // Data needs deletion. Check if trash may be skipped.
      // Trash may be skipped iff:
      //  1. deleteData == true, obviously.
      //  2. tbl is external.
      //  3. Either
      //    3.1. User has specified PURGE from the commandline, and if not,
      //    3.2. User has set the table to auto-purge.
      return ((envContext != null) && Boolean.parseBoolean(envContext.getProperties().get("ifPurge")))
        || (tbl.isSetParameters() && "true".equalsIgnoreCase(tbl.getParameters().get("auto.purge")));

    }
    private void deleteParentRecursive(Path parent, int depth, boolean mustPurge) throws IOException, MetaException {
      if (depth > 0 && parent != null && wh.isWritable(parent) && wh.isEmpty(parent)) {
        wh.deleteDir(parent, true, mustPurge);
        deleteParentRecursive(parent.getParent(), depth - 1, mustPurge);
      }
    }

    @Override
    public boolean drop_partition(final String db_name, final String tbl_name,
        final List<String> part_vals, final boolean deleteData)
        throws NoSuchObjectException, MetaException, TException {
      return drop_partition_with_environment_context(db_name, tbl_name, part_vals, deleteData,
          null);
    }

    private static class PathAndPartValSize {
      public PathAndPartValSize(Path path, int partValSize) {
        this.path = path;
        this.partValSize = partValSize;
      }
      public Path path;
      public int partValSize;
    }

    @Override
    public DropPartitionsResult drop_partitions_req(
        DropPartitionsRequest request) throws MetaException, NoSuchObjectException, TException {
      RawStore ms = getMS();
      String dbName = request.getDbName(), tblName = request.getTblName();
      boolean ifExists = request.isSetIfExists() && request.isIfExists();
      boolean deleteData = request.isSetDeleteData() && request.isDeleteData();
      boolean ignoreProtection = request.isSetIgnoreProtection() && request.isIgnoreProtection();
      boolean needResult = !request.isSetNeedResult() || request.isNeedResult();
      List<PathAndPartValSize> dirsToDelete = new ArrayList<PathAndPartValSize>();
      List<Path> archToDelete = new ArrayList<Path>();
      EnvironmentContext envContext = request.isSetEnvironmentContext()
          ? request.getEnvironmentContext() : null;

      boolean success = false;
      ms.openTransaction();
      Table tbl = null;
      List<Partition> parts = null;
      boolean mustPurge = false;
      boolean isExternalTbl = false;
      try {
        // We need Partition-s for firing events and for result; DN needs MPartition-s to drop.
        // Great... Maybe we could bypass fetching MPartitions by issuing direct SQL deletes.
        tbl = get_table_core(dbName, tblName);
        isExternalTbl = isExternal(tbl);
        mustPurge = isMustPurge(envContext, tbl);
        int minCount = 0;
        RequestPartsSpec spec = request.getParts();
        List<String> partNames = null;
        if (spec.isSetExprs()) {
          // Dropping by expressions.
          parts = new ArrayList<Partition>(spec.getExprs().size());
          for (DropPartitionsExpr expr : spec.getExprs()) {
            ++minCount; // At least one partition per expression, if not ifExists
            List<Partition> result = new ArrayList<Partition>();
            boolean hasUnknown = ms.getPartitionsByExpr(
                dbName, tblName, expr.getExpr(), null, (short)-1, result);
            if (hasUnknown) {
              // Expr is built by DDLSA, it should only contain part cols and simple ops
              throw new MetaException("Unexpected unknown partitions to drop");
            }
            // this is to prevent dropping archived partition which is archived in a
            // different level the drop command specified.
            if (!ignoreProtection && expr.isSetPartArchiveLevel()) {
              for (Partition part : parts) {
                if (MetaStoreUtils.isArchived(part)
                    && MetaStoreUtils.getArchivingLevel(part) < expr.getPartArchiveLevel()) {
                  throw new MetaException("Cannot drop a subset of partitions "
                      + " in an archive, partition " + part);
                }
              }
            }
            parts.addAll(result);
          }
        } else if (spec.isSetNames()) {
          partNames = spec.getNames();
          minCount = partNames.size();
          parts = ms.getPartitionsByNames(dbName, tblName, partNames);
        } else {
          throw new MetaException("Partition spec is not set");
        }

        if ((parts.size() < minCount) && !ifExists) {
          throw new NoSuchObjectException("Some partitions to drop are missing");
        }

        List<String> colNames = null;
        if (partNames == null) {
          partNames = new ArrayList<String>(parts.size());
          colNames = new ArrayList<String>(tbl.getPartitionKeys().size());
          for (FieldSchema col : tbl.getPartitionKeys()) {
            colNames.add(col.getName());
          }
        }

        for (Partition part : parts) {
          if (!ignoreProtection && !MetaStoreUtils.canDropPartition(tbl, part)) {
            throw new MetaException("Table " + tbl.getTableName()
                + " Partition " + part + " is protected from being dropped");
          }

          firePreEvent(new PreDropPartitionEvent(tbl, part, deleteData, this));
          if (colNames != null) {
            partNames.add(FileUtils.makePartName(colNames, part.getValues()));
          }
          // Preserve the old behavior of failing when we cannot write, even w/o deleteData,
          // and even if the table is external. That might not make any sense.
          if (MetaStoreUtils.isArchived(part)) {
            Path archiveParentDir = MetaStoreUtils.getOriginalLocation(part);
            verifyIsWritablePath(archiveParentDir);
            checkTrashPurgeCombination(archiveParentDir, dbName + "." + tblName + "." +
                part.getValues(), mustPurge, deleteData && !isExternalTbl);
            archToDelete.add(archiveParentDir);
          }
          if ((part.getSd() != null) && (part.getSd().getLocation() != null)) {
            Path partPath = new Path(part.getSd().getLocation());
            verifyIsWritablePath(partPath);
            checkTrashPurgeCombination(partPath, dbName + "." + tblName + "." + part.getValues(),
                mustPurge, deleteData && !isExternalTbl);
            dirsToDelete.add(new PathAndPartValSize(partPath, part.getValues().size()));
          }
        }

        ms.dropPartitions(dbName, tblName, partNames);
        success = ms.commitTransaction();
        DropPartitionsResult result = new DropPartitionsResult();
        if (needResult) {
          result.setPartitions(parts);
        }
        return result;
      } finally {
        if (!success) {
          ms.rollbackTransaction();
        } else if (deleteData && !isExternal(tbl)) {
          LOG.info( mustPurge?
                      "dropPartition() will purge partition-directories directly, skipping trash."
                    :  "dropPartition() will move partition-directories to trash-directory.");
          // Archived partitions have har:/to_har_file as their location.
          // The original directory was saved in params
          for (Path path : archToDelete) {
            wh.deleteDir(path, true, mustPurge);
          }
          for (PathAndPartValSize p : dirsToDelete) {
            wh.deleteDir(p.path, true, mustPurge);
            try {
              deleteParentRecursive(p.path.getParent(), p.partValSize - 1, mustPurge);
            } catch (IOException ex) {
              LOG.warn("Error from deleteParentRecursive", ex);
              throw new MetaException("Failed to delete parent: " + ex.getMessage());
            }
          }
        }
        if (parts != null) {
          for (Partition part : parts) {
            for (MetaStoreEventListener listener : listeners) {
              DropPartitionEvent dropPartitionEvent =
                new DropPartitionEvent(tbl, part, success, deleteData, this);
              dropPartitionEvent.setEnvironmentContext(envContext);
              listener.onDropPartition(dropPartitionEvent);
            }
          }
        }
      }
    }

    private void verifyIsWritablePath(Path dir) throws MetaException {
      try {
        if (!wh.isWritable(dir.getParent())) {
          throw new MetaException("Table partition not deleted since " + dir.getParent()
              + " is not writable by " + hiveConf.getUser());
        }
      } catch (IOException ex) {
        LOG.warn("Error from isWritable", ex);
        throw new MetaException("Table partition not deleted since " + dir.getParent()
            + " access cannot be checked: " + ex.getMessage());
      }
    }

    @Override
    public boolean drop_partition_with_environment_context(final String db_name,
        final String tbl_name, final List<String> part_vals, final boolean deleteData,
        final EnvironmentContext envContext)
        throws NoSuchObjectException, MetaException, TException {
      startPartitionFunction("drop_partition", db_name, tbl_name, part_vals);
      LOG.info("Partition values:" + part_vals);

      boolean ret = false;
      Exception ex = null;
      try {
        ret = drop_partition_common(getMS(), db_name, tbl_name, part_vals, deleteData, envContext);
      } catch (IOException e) {
        ex = e;
        throw new MetaException(e.getMessage());
      } catch (Exception e) {
        ex = e;
        rethrowException(e);
      } finally {
        endFunction("drop_partition", ret, ex, tbl_name);
      }
      return ret;

    }

    @Override
    public Partition get_partition(final String db_name, final String tbl_name,
        final List<String> part_vals) throws MetaException, NoSuchObjectException {
      startPartitionFunction("get_partition", db_name, tbl_name, part_vals);

      Partition ret = null;
      Exception ex = null;
      try {
        fireReadTablePreEvent(db_name, tbl_name);
        ret = getMS().getPartition(db_name, tbl_name, part_vals);
      } catch (Exception e) {
        ex = e;
        if (e instanceof MetaException) {
          throw (MetaException) e;
        } else if (e instanceof NoSuchObjectException) {
          throw (NoSuchObjectException) e;
        } else {
          throw newMetaException(e);
        }
      } finally {
        endFunction("get_partition", ret != null, ex, tbl_name);
      }
      return ret;
    }

    /**
     * Fire a pre-event for read table operation, if there are any
     * pre-event listeners registered
     *
     * @param db_name
     * @param tbl_name
     * @throws MetaException
     * @throws NoSuchObjectException
     */
    private void fireReadTablePreEvent(String dbName, String tblName) throws MetaException, NoSuchObjectException {
      if(preListeners.size() > 0) {
        // do this only if there is a pre event listener registered (avoid unnecessary
        // metastore api call)
        Table t = getMS().getTable(dbName, tblName);
        if (t == null) {
          throw new NoSuchObjectException(dbName + "." + tblName
              + " table not found");
        }
        firePreEvent(new PreReadTableEvent(t, this));
      }
    }

    @Override
    public Partition get_partition_with_auth(final String db_name,
        final String tbl_name, final List<String> part_vals,
        final String user_name, final List<String> group_names)
        throws MetaException, NoSuchObjectException, TException {
      startPartitionFunction("get_partition_with_auth", db_name, tbl_name,
          part_vals);
      fireReadTablePreEvent(db_name, tbl_name);
      Partition ret = null;
      Exception ex = null;
      try {
        ret = getMS().getPartitionWithAuth(db_name, tbl_name, part_vals,
            user_name, group_names);
      } catch (InvalidObjectException e) {
        ex = e;
        throw new NoSuchObjectException(e.getMessage());
      } catch (Exception e) {
        ex = e;
        rethrowException(e);
      } finally {
        endFunction("get_partition_with_auth", ret != null, ex, tbl_name);
      }
      return ret;
    }

    @Override
    public List<Partition> get_partitions(final String db_name, final String tbl_name,
        final short max_parts) throws NoSuchObjectException, MetaException {
      startTableFunction("get_partitions", db_name, tbl_name);
      fireReadTablePreEvent(db_name, tbl_name);
      List<Partition> ret = null;
      Exception ex = null;
      try {
        checkLimitNumberOfPartitionsByFilter(db_name, tbl_name, NO_FILTER_STRING, max_parts);
        ret = getMS().getPartitions(db_name, tbl_name, max_parts);
      } catch (Exception e) {
        ex = e;
        if (e instanceof MetaException) {
          throw (MetaException) e;
        } else if (e instanceof NoSuchObjectException) {
          throw (NoSuchObjectException) e;
        } else {
          throw newMetaException(e);
        }
      } finally {
        endFunction("get_partitions", ret != null, ex, tbl_name);
      }
      return ret;

    }

    @Override
    public List<Partition> get_partitions_with_auth(final String dbName,
        final String tblName, final short maxParts, final String userName,
        final List<String> groupNames) throws NoSuchObjectException,
        MetaException, TException {
      startTableFunction("get_partitions_with_auth", dbName, tblName);

      List<Partition> ret = null;
      Exception ex = null;
      try {
        checkLimitNumberOfPartitionsByFilter(dbName, tblName, NO_FILTER_STRING, maxParts);
        ret = getMS().getPartitionsWithAuth(dbName, tblName, maxParts,
            userName, groupNames);
      } catch (InvalidObjectException e) {
        ex = e;
        throw new NoSuchObjectException(e.getMessage());
      } catch (Exception e) {
        ex = e;
        rethrowException(e);
      } finally {
        endFunction("get_partitions_with_auth", ret != null, ex, tblName);
      }
      return ret;

    }

    private void checkLimitNumberOfPartitionsByFilter(String dbName, String tblName, String filterString, int maxParts) throws TException {
      if (isPartitionLimitEnabled()) {
        checkLimitNumberOfPartitions(tblName, get_num_partitions_by_filter(dbName, tblName, filterString), maxParts);
      }
    }

    private void checkLimitNumberOfPartitionsByExpr(String dbName, String tblName, byte[] filterExpr, int maxParts) throws TException {
      if (isPartitionLimitEnabled()) {
        checkLimitNumberOfPartitions(tblName, get_num_partitions_by_expr(dbName, tblName, filterExpr), maxParts);
      }
    }

    private boolean isPartitionLimitEnabled() {
      int partitionLimit = HiveConf.getIntVar(hiveConf, HiveConf.ConfVars.METASTORE_LIMIT_PARTITION_REQUEST);
      return partitionLimit > -1;
    }

    private void checkLimitNumberOfPartitions(String tblName, int numPartitions, int maxToFetch) throws MetaException {
      if (isPartitionLimitEnabled()) {
        int partitionLimit = HiveConf.getIntVar(hiveConf, HiveConf.ConfVars.METASTORE_LIMIT_PARTITION_REQUEST);
        int partitionRequest = (maxToFetch < 0) ? numPartitions : maxToFetch;
        if (partitionRequest > partitionLimit) {
          String configName = ConfVars.METASTORE_LIMIT_PARTITION_REQUEST.varname;
          throw new MetaException(String.format("Number of partitions scanned (=%d) on table '%s' exceeds limit" +
              " (=%d). This is controlled on the metastore server by %s.", partitionRequest, tblName, partitionLimit, configName));
        }
      }
    }

    @Override
    public List<PartitionSpec> get_partitions_pspec(final String db_name, final String tbl_name, final int max_parts)
      throws NoSuchObjectException, MetaException  {

      String dbName = db_name.toLowerCase();
      String tableName = tbl_name.toLowerCase();

      startTableFunction("get_partitions_pspec", dbName, tableName);

      List<PartitionSpec> partitionSpecs = null;
      try {
        Table table = get_table_core(dbName, tableName);
        List<Partition> partitions = get_partitions(dbName, tableName, (short) max_parts);

        if (is_partition_spec_grouping_enabled(table)) {
          partitionSpecs = get_partitionspecs_grouped_by_storage_descriptor(table, partitions);
        }
        else {
          PartitionSpec pSpec = new PartitionSpec();
          pSpec.setPartitionList(new PartitionListComposingSpec(partitions));
          pSpec.setDbName(dbName);
          pSpec.setTableName(tableName);
          pSpec.setRootPath(table.getSd().getLocation());
          partitionSpecs = Arrays.asList(pSpec);
        }

        return partitionSpecs;
      }
      finally {
        endFunction("get_partitions_pspec", partitionSpecs != null && !partitionSpecs.isEmpty(), null, tbl_name);
      }
    }

    private static class StorageDescriptorKey {

      private final StorageDescriptor sd;

      StorageDescriptorKey(StorageDescriptor sd) { this.sd = sd; }

      StorageDescriptor getSd() {
        return sd;
      }

      private String hashCodeKey() {
        return sd.getInputFormat() + "\t"
            + sd.getOutputFormat() +  "\t"
            + sd.getSerdeInfo().getSerializationLib() + "\t"
            + sd.getCols();
      }

      @Override
      public int hashCode() {
        return hashCodeKey().hashCode();
      }

      @Override
      public boolean equals(Object rhs) {
        if (rhs == this)
          return true;

        if (!(rhs instanceof StorageDescriptorKey))
          return false;

        return (hashCodeKey().equals(((StorageDescriptorKey) rhs).hashCodeKey()));
      }
    }

    private List<PartitionSpec> get_partitionspecs_grouped_by_storage_descriptor(Table table, List<Partition> partitions)
      throws NoSuchObjectException, MetaException {

      assert is_partition_spec_grouping_enabled(table);

      final String tablePath = table.getSd().getLocation();

      ImmutableListMultimap<Boolean, Partition> partitionsWithinTableDirectory
          = Multimaps.index(partitions, new com.google.common.base.Function<Partition, Boolean>() {

        @Override
        public Boolean apply(Partition input) {
          return input.getSd().getLocation().startsWith(tablePath);
        }
      });

      List<PartitionSpec> partSpecs = new ArrayList<PartitionSpec>();

      // Classify partitions within the table directory into groups,
      // based on shared SD properties.

      Map<StorageDescriptorKey, List<PartitionWithoutSD>> sdToPartList
          = new HashMap<StorageDescriptorKey, List<PartitionWithoutSD>>();

      if (partitionsWithinTableDirectory.containsKey(true)) {

        ImmutableList<Partition> partsWithinTableDir = partitionsWithinTableDirectory.get(true);
        for (Partition partition : partsWithinTableDir) {

          PartitionWithoutSD partitionWithoutSD
              = new PartitionWithoutSD( partition.getValues(),
              partition.getCreateTime(),
              partition.getLastAccessTime(),
              partition.getSd().getLocation().substring(tablePath.length()), partition.getParameters());

          StorageDescriptorKey sdKey = new StorageDescriptorKey(partition.getSd());
          if (!sdToPartList.containsKey(sdKey)) {
            sdToPartList.put(sdKey, new ArrayList<PartitionWithoutSD>());
          }

          sdToPartList.get(sdKey).add(partitionWithoutSD);

        } // for (partitionsWithinTableDirectory);

        for (Map.Entry<StorageDescriptorKey, List<PartitionWithoutSD>> entry : sdToPartList.entrySet()) {
          partSpecs.add(getSharedSDPartSpec(table, entry.getKey(), entry.getValue()));
        }

      } // Done grouping partitions within table-dir.

      // Lump all partitions outside the tablePath into one PartSpec.
      if (partitionsWithinTableDirectory.containsKey(false)) {
        List<Partition> partitionsOutsideTableDir = partitionsWithinTableDirectory.get(false);
        if (!partitionsOutsideTableDir.isEmpty()) {
          PartitionSpec partListSpec = new PartitionSpec();
          partListSpec.setDbName(table.getDbName());
          partListSpec.setTableName(table.getTableName());
          partListSpec.setPartitionList(new PartitionListComposingSpec(partitionsOutsideTableDir));
          partSpecs.add(partListSpec);
        }

      }
      return partSpecs;
    }

    private PartitionSpec getSharedSDPartSpec(Table table, StorageDescriptorKey sdKey, List<PartitionWithoutSD> partitions) {

      StorageDescriptor sd = new StorageDescriptor(sdKey.getSd());
      sd.setLocation(table.getSd().getLocation()); // Use table-dir as root-dir.
      PartitionSpecWithSharedSD sharedSDPartSpec =
          new PartitionSpecWithSharedSD(partitions, sd);

      PartitionSpec ret = new PartitionSpec();
      ret.setRootPath(sd.getLocation());
      ret.setSharedSDPartitionSpec(sharedSDPartSpec);
      ret.setDbName(table.getDbName());
      ret.setTableName(table.getTableName());

      return ret;
    }

    private static boolean is_partition_spec_grouping_enabled(Table table) {

      Map<String, String> parameters = table.getParameters();
      return parameters.containsKey("hive.hcatalog.partition.spec.grouping.enabled")
          && parameters.get("hive.hcatalog.partition.spec.grouping.enabled").equalsIgnoreCase("true");
    }

    @Override
    public List<String> get_partition_names(final String db_name, final String tbl_name,
        final short max_parts) throws MetaException, NoSuchObjectException {
      startTableFunction("get_partition_names", db_name, tbl_name);
      fireReadTablePreEvent(db_name, tbl_name);
      List<String> ret = null;
      Exception ex = null;
      try {
        ret = getMS().listPartitionNames(db_name, tbl_name, max_parts);
      } catch (Exception e) {
        ex = e;
        if (e instanceof MetaException) {
          throw (MetaException) e;
        } else {
          throw newMetaException(e);
        }
      } finally {
        endFunction("get_partition_names", ret != null, ex, tbl_name);
      }
      return ret;
    }

    @Override
    public void alter_partition(final String db_name, final String tbl_name,
        final Partition new_part)
        throws InvalidOperationException, MetaException,
        TException {
      rename_partition(db_name, tbl_name, null, new_part);
    }

    @Override
    public void alter_partition_with_environment_context(final String dbName,
        final String tableName, final Partition newPartition,
        final EnvironmentContext envContext)
        throws InvalidOperationException, MetaException, TException {
      rename_partition(dbName, tableName, null,
          newPartition, envContext);
    }

    @Override
    public void rename_partition(final String db_name, final String tbl_name,
        final List<String> part_vals, final Partition new_part)
        throws InvalidOperationException, MetaException, TException {
      // Call rename_partition without an environment context.
      rename_partition(db_name, tbl_name, part_vals, new_part, null);
    }

    private void rename_partition(final String db_name, final String tbl_name,
        final List<String> part_vals, final Partition new_part,
        final EnvironmentContext envContext)
        throws InvalidOperationException, MetaException,
        TException {
      startTableFunction("alter_partition", db_name, tbl_name);

      if (LOG.isInfoEnabled()) {
        LOG.info("New partition values:" + new_part.getValues());
        if (part_vals != null && part_vals.size() > 0) {
          LOG.info("Old Partition values:" + part_vals);
        }
      }

      // Adds the missing scheme/authority for the new partition location
      if (new_part.getSd() != null) {
        String newLocation = new_part.getSd().getLocation();
        if (org.apache.commons.lang.StringUtils.isNotEmpty(newLocation)) {
          Path tblPath = wh.getDnsPath(new Path(newLocation));
          new_part.getSd().setLocation(tblPath.toString());
        }
      }

      Partition oldPart = null;
      Exception ex = null;
      try {
        firePreEvent(new PreAlterPartitionEvent(db_name, tbl_name, part_vals, new_part, this));

        if (part_vals != null && !part_vals.isEmpty()) {
          MetaStoreUtils.validatePartitionNameCharacters(new_part.getValues(),
              partitionValidationPattern);
        }

        oldPart = alterHandler.alterPartition(getMS(), wh, db_name, tbl_name, part_vals, new_part);

        // Only fetch the table if we actually have a listener
        Table table = null;
        for (MetaStoreEventListener listener : listeners) {
          if (table == null) {
            table = getMS().getTable(db_name, tbl_name);
          }
          AlterPartitionEvent alterPartitionEvent =
              new AlterPartitionEvent(oldPart, new_part, table, true, this);
          alterPartitionEvent.setEnvironmentContext(envContext);
          listener.onAlterPartition(alterPartitionEvent);
        }
      } catch (InvalidObjectException e) {
        ex = e;
        throw new InvalidOperationException(e.getMessage());
      } catch (AlreadyExistsException e) {
        ex = e;
        throw new InvalidOperationException(e.getMessage());
      } catch (Exception e) {
        ex = e;
        if (e instanceof MetaException) {
          throw (MetaException) e;
        } else if (e instanceof InvalidOperationException) {
          throw (InvalidOperationException) e;
        } else if (e instanceof TException) {
          throw (TException) e;
        } else {
          throw newMetaException(e);
        }
      } finally {
        endFunction("alter_partition", oldPart != null, ex, tbl_name);
      }
      return;
    }

    @Override
    public void alter_partitions(final String db_name, final String tbl_name,
        final List<Partition> new_parts)
        throws InvalidOperationException, MetaException,
        TException {

      startTableFunction("alter_partitions", db_name, tbl_name);

      if (LOG.isInfoEnabled()) {
        for (Partition tmpPart : new_parts) {
          LOG.info("New partition values:" + tmpPart.getValues());
        }
      }
      // all partitions are altered atomically
      // all prehooks are fired together followed by all post hooks
      List<Partition> oldParts = null;
      Exception ex = null;
      try {
        for (Partition tmpPart : new_parts) {
          firePreEvent(new PreAlterPartitionEvent(db_name, tbl_name, null, tmpPart, this));
        }
        oldParts = alterHandler.alterPartitions(getMS(), wh, db_name, tbl_name, new_parts);

        Iterator<Partition> olditr = oldParts.iterator();
        // Only fetch the table if we have a listener that needs it.
        Table table = null;
        for (Partition tmpPart : new_parts) {
          Partition oldTmpPart = null;
          if (olditr.hasNext()) {
            oldTmpPart = olditr.next();
          }
          else {
            throw new InvalidOperationException("failed to alterpartitions");
          }
          for (MetaStoreEventListener listener : listeners) {
            if (table == null) {
              table = getMS().getTable(db_name, tbl_name);
            }
            AlterPartitionEvent alterPartitionEvent =
                new AlterPartitionEvent(oldTmpPart, tmpPart, table, true, this);
            listener.onAlterPartition(alterPartitionEvent);
          }
        }
      } catch (InvalidObjectException e) {
        ex = e;
        throw new InvalidOperationException(e.getMessage());
      } catch (AlreadyExistsException e) {
        ex = e;
        throw new InvalidOperationException(e.getMessage());
      } catch (Exception e) {
        ex = e;
        if (e instanceof MetaException) {
          throw (MetaException) e;
        } else if (e instanceof InvalidOperationException) {
          throw (InvalidOperationException) e;
        } else if (e instanceof TException) {
          throw (TException) e;
        } else {
          throw newMetaException(e);
        }
      } finally {
        endFunction("alter_partition", oldParts != null, ex, tbl_name);
      }
      return;
    }

    @Override
    public void alter_index(final String dbname, final String base_table_name,
        final String index_name, final Index newIndex)
        throws InvalidOperationException, MetaException {
      startFunction("alter_index", ": db=" + dbname + " base_tbl=" + base_table_name
          + " idx=" + index_name + " newidx=" + newIndex.getIndexName());
      newIndex.putToParameters(hive_metastoreConstants.DDL_TIME, Long.toString(System
          .currentTimeMillis() / 1000));

      boolean success = false;
      Exception ex = null;
      Index oldIndex = null;
      try {
        oldIndex = get_index_by_name(dbname, base_table_name, index_name);

        firePreEvent(new PreAlterIndexEvent(oldIndex, newIndex, this));

        getMS().alterIndex(dbname, base_table_name, index_name, newIndex);
        success = true;
      } catch (InvalidObjectException e) {
        ex = e;
        throw new InvalidOperationException(e.getMessage());
      } catch (Exception e) {
        ex = e;
        if (e instanceof MetaException) {
          throw (MetaException) e;
        } else if (e instanceof InvalidOperationException) {
          throw (InvalidOperationException) e;
        } else {
          throw newMetaException(e);
        }
      } finally {
        endFunction("alter_index", success, ex, base_table_name);
        for (MetaStoreEventListener listener : listeners) {
          AlterIndexEvent alterIndexEvent = new AlterIndexEvent(oldIndex, newIndex, success, this);
          listener.onAlterIndex(alterIndexEvent);
        }
      }
      return;
    }

    @Override
    public String getVersion() throws TException {
      endFunction(startFunction("getVersion"), true, null);
      return "3.0";
    }

    @Override
    public void alter_table(final String dbname, final String name,
        final Table newTable)
        throws InvalidOperationException, MetaException {
      // Do not set an environment context.
      alter_table_core(dbname,name, newTable, null, false);
    }

    @Override
    public void alter_table_with_cascade(final String dbname, final String name,
        final Table newTable, final boolean cascade)
        throws InvalidOperationException, MetaException {
      // Do not set an environment context.
      alter_table_core(dbname,name, newTable, null, cascade);
    }

    @Override
    public void alter_table_with_environment_context(final String dbname,
        final String name, final Table newTable,
        final EnvironmentContext envContext)
        throws InvalidOperationException, MetaException {
      alter_table_core(dbname, name, newTable, envContext, false);
    }

    private void alter_table_core(final String dbname, final String name, final Table newTable,
        final EnvironmentContext envContext, final boolean cascade)
        throws InvalidOperationException, MetaException {
      startFunction("alter_table", ": db=" + dbname + " tbl=" + name
          + " newtbl=" + newTable.getTableName());

      // Update the time if it hasn't been specified.
      if (newTable.getParameters() == null ||
          newTable.getParameters().get(hive_metastoreConstants.DDL_TIME) == null) {
        newTable.putToParameters(hive_metastoreConstants.DDL_TIME, Long.toString(System
            .currentTimeMillis() / 1000));
      }

      // Adds the missing scheme/authority for the new table location
      if (newTable.getSd() != null) {
        String newLocation = newTable.getSd().getLocation();
        if (org.apache.commons.lang.StringUtils.isNotEmpty(newLocation)) {
          Path tblPath = wh.getDnsPath(new Path(newLocation));
          newTable.getSd().setLocation(tblPath.toString());
        }
      }

      boolean success = false;
      Exception ex = null;
      try {
        Table oldt = get_table_core(dbname, name);
        firePreEvent(new PreAlterTableEvent(oldt, newTable, this));
        alterHandler.alterTable(getMS(), wh, dbname, name, newTable, cascade);
        success = true;

        for (MetaStoreEventListener listener : listeners) {
          AlterTableEvent alterTableEvent =
              new AlterTableEvent(oldt, newTable, success, this);
          alterTableEvent.setEnvironmentContext(envContext);
          listener.onAlterTable(alterTableEvent);
        }
      } catch (NoSuchObjectException e) {
        // thrown when the table to be altered does not exist
        ex = e;
        throw new InvalidOperationException(e.getMessage());
      } catch (Exception e) {
        ex = e;
        if (e instanceof MetaException) {
          throw (MetaException) e;
        } else if (e instanceof InvalidOperationException) {
          throw (InvalidOperationException) e;
        } else {
          throw newMetaException(e);
        }
      } finally {
        endFunction("alter_table", success, ex, name);
      }
    }

    @Override
    public List<String> get_tables(final String dbname, final String pattern)
        throws MetaException {
      startFunction("get_tables", ": db=" + dbname + " pat=" + pattern);

      List<String> ret = null;
      Exception ex = null;
      try {
        ret = getMS().getTables(dbname, pattern);
      } catch (Exception e) {
        ex = e;
        if (e instanceof MetaException) {
          throw (MetaException) e;
        } else {
          throw newMetaException(e);
        }
      } finally {
        endFunction("get_tables", ret != null, ex);
      }
      return ret;
    }

    @Override
    public List<String> get_all_tables(final String dbname) throws MetaException {
      startFunction("get_all_tables", ": db=" + dbname);

      List<String> ret = null;
      Exception ex = null;
      try {
        ret = getMS().getAllTables(dbname);
      } catch (Exception e) {
        ex = e;
        if (e instanceof MetaException) {
          throw (MetaException) e;
        } else {
          throw newMetaException(e);
        }
      } finally {
        endFunction("get_all_tables", ret != null, ex);
      }
      return ret;
    }

    @Override
    public List<FieldSchema> get_fields(String db, String tableName)
        throws MetaException, UnknownTableException, UnknownDBException {
      return get_fields_with_environment_context(db, tableName, null);
    }

    @Override
    public List<FieldSchema> get_fields_with_environment_context(String db, String tableName,
        final EnvironmentContext envContext)
        throws MetaException, UnknownTableException, UnknownDBException {
      startFunction("get_fields_with_environment_context", ": db=" + db + "tbl=" + tableName);
      String[] names = tableName.split("\\.");
      String base_table_name = names[0];

      Table tbl;
      List<FieldSchema> ret = null;
      Exception ex = null;
      ClassLoader orgHiveLoader = null;
      Configuration curConf = hiveConf;
      try {
        try {
          tbl = get_table_core(db, base_table_name);
        } catch (NoSuchObjectException e) {
          throw new UnknownTableException(e.getMessage());
        }
        if (null == tbl.getSd().getSerdeInfo().getSerializationLib() ||
          hiveConf.getStringCollection(ConfVars.SERDESUSINGMETASTOREFORSCHEMA.varname).contains
          (tbl.getSd().getSerdeInfo().getSerializationLib())) {
          ret = tbl.getSd().getCols();
        } else {
          try {
            if (envContext != null) {
              String addedJars = envContext.getProperties().get("hive.added.jars.path");
              if (org.apache.commons.lang.StringUtils.isNotBlank(addedJars)) {
                //for thread safe
                curConf = getConf();
                orgHiveLoader = curConf.getClassLoader();
                ClassLoader loader = MetaStoreUtils.addToClassPath(orgHiveLoader, org.apache.commons.lang.StringUtils.split(addedJars, ","));
                curConf.setClassLoader(loader);
              }
            }

            Deserializer s = MetaStoreUtils.getDeserializer(curConf, tbl, false);
            ret = MetaStoreUtils.getFieldsFromDeserializer(tableName, s);
          } catch (SerDeException e) {
            StringUtils.stringifyException(e);
            throw new MetaException(e.getMessage());
          }
        }
      } catch (Exception e) {
        ex = e;
        if (e instanceof UnknownDBException) {
          throw (UnknownDBException) e;
        } else if (e instanceof UnknownTableException) {
          throw (UnknownTableException) e;
        } else if (e instanceof MetaException) {
          throw (MetaException) e;
        } else {
          throw newMetaException(e);
        }
      } finally {
        if (orgHiveLoader != null) {
          curConf.setClassLoader(orgHiveLoader);
        }
        endFunction("get_fields_with_environment_context", ret != null, ex, tableName);
      }

      return ret;
    }

    /**
     * Return the schema of the table. This function includes partition columns
     * in addition to the regular columns.
     *
     * @param db
     *          Name of the database
     * @param tableName
     *          Name of the table
     * @return List of columns, each column is a FieldSchema structure
     * @throws MetaException
     * @throws UnknownTableException
     * @throws UnknownDBException
     */
    @Override
    public List<FieldSchema> get_schema(String db, String tableName)
        throws MetaException, UnknownTableException, UnknownDBException {
      return get_schema_with_environment_context(db,tableName, null);
    }


    /**
     * Return the schema of the table. This function includes partition columns
     * in addition to the regular columns.
     *
     * @param db
     *          Name of the database
     * @param tableName
     *          Name of the table
     * @param envContext
     *          Store session based properties
     * @return List of columns, each column is a FieldSchema structure
     * @throws MetaException
     * @throws UnknownTableException
     * @throws UnknownDBException
     */
    @Override
    public List<FieldSchema> get_schema_with_environment_context(String db, String tableName,
          final EnvironmentContext envContext)
        throws MetaException, UnknownTableException, UnknownDBException {
      startFunction("get_schema_with_environment_context", ": db=" + db + "tbl=" + tableName);
      boolean success = false;
      Exception ex = null;
      try {
        String[] names = tableName.split("\\.");
        String base_table_name = names[0];

        Table tbl;
        try {
          tbl = get_table_core(db, base_table_name);
        } catch (NoSuchObjectException e) {
          throw new UnknownTableException(e.getMessage());
        }
        List<FieldSchema> fieldSchemas = get_fields_with_environment_context(db, base_table_name,envContext);

        if (tbl == null || fieldSchemas == null) {
          throw new UnknownTableException(tableName + " doesn't exist");
        }

        if (tbl.getPartitionKeys() != null) {
          // Combine the column field schemas and the partition keys to create the
          // whole schema
          fieldSchemas.addAll(tbl.getPartitionKeys());
        }
        success = true;
        return fieldSchemas;
      } catch (Exception e) {
        ex = e;
        if (e instanceof UnknownDBException) {
          throw (UnknownDBException) e;
        } else if (e instanceof UnknownTableException) {
          throw (UnknownTableException) e;
        } else if (e instanceof MetaException) {
          throw (MetaException) e;
        } else {
          MetaException me = new MetaException(e.toString());
          me.initCause(e);
          throw me;
        }
      } finally {
        endFunction("get_schema_with_environment_context", success, ex, tableName);
      }
    }

    @Override
    public String getCpuProfile(int profileDurationInSec) throws TException {
      return "";
    }

    /**
     * Returns the value of the given configuration variable name. If the
     * configuration variable with the given name doesn't exist, or if there
     * were an exception thrown while retrieving the variable, or if name is
     * null, defaultValue is returned.
     */
    @Override
    public String get_config_value(String name, String defaultValue)
        throws TException, ConfigValSecurityException {
      startFunction("get_config_value", ": name=" + name + " defaultValue="
          + defaultValue);
      boolean success = false;
      Exception ex = null;
      try {
        if (name == null) {
          success = true;
          return defaultValue;
        }
        // Allow only keys that start with hive.*, hdfs.*, mapred.* for security
        // i.e. don't allow access to db password
        if (!Pattern.matches("(hive|hdfs|mapred).*", name)) {
          throw new ConfigValSecurityException("For security reasons, the "
              + "config key " + name + " cannot be accessed");
        }

        String toReturn = defaultValue;
        try {
          toReturn = hiveConf.get(name, defaultValue);
        } catch (RuntimeException e) {
          LOG.error(threadLocalId.get().toString() + ": "
              + "RuntimeException thrown in get_config_value - msg: "
              + e.getMessage() + " cause: " + e.getCause());
        }
        success = true;
        return toReturn;
      } catch (Exception e) {
        ex = e;
        if (e instanceof ConfigValSecurityException) {
          throw (ConfigValSecurityException) e;
        } else if (e instanceof TException) {
          throw (TException) e;
        } else {
          TException te = new TException(e.toString());
          te.initCause(e);
          throw te;
        }
      } finally {
        endFunction("get_config_value", success, ex);
      }
    }

    private List<String> getPartValsFromName(RawStore ms, String dbName, String tblName,
        String partName) throws MetaException, InvalidObjectException {
      // Unescape the partition name
      LinkedHashMap<String, String> hm = Warehouse.makeSpecFromName(partName);

      // getPartition expects partition values in a list. use info from the
      // table to put the partition column values in order
      Table t = ms.getTable(dbName, tblName);
      if (t == null) {
        throw new InvalidObjectException(dbName + "." + tblName
            + " table not found");
      }

      List<String> partVals = new ArrayList<String>();
      for (FieldSchema field : t.getPartitionKeys()) {
        String key = field.getName();
        String val = hm.get(key);
        if (val == null) {
          throw new InvalidObjectException("incomplete partition name - missing " + key);
        }
        partVals.add(val);
      }
      return partVals;
    }

    private Partition get_partition_by_name_core(final RawStore ms, final String db_name,
        final String tbl_name, final String part_name)
        throws MetaException, NoSuchObjectException, TException {
      fireReadTablePreEvent(db_name, tbl_name);
      List<String> partVals = null;
      try {
        partVals = getPartValsFromName(ms, db_name, tbl_name, part_name);
      } catch (InvalidObjectException e) {
        throw new NoSuchObjectException(e.getMessage());
      }
      Partition p = ms.getPartition(db_name, tbl_name, partVals);

      if (p == null) {
        throw new NoSuchObjectException(db_name + "." + tbl_name
            + " partition (" + part_name + ") not found");
      }
      return p;
    }

    @Override
    public Partition get_partition_by_name(final String db_name, final String tbl_name,
        final String part_name) throws MetaException, NoSuchObjectException, TException {

      startFunction("get_partition_by_name", ": db=" + db_name + " tbl="
          + tbl_name + " part=" + part_name);
      Partition ret = null;
      Exception ex = null;
      try {
        ret = get_partition_by_name_core(getMS(), db_name, tbl_name, part_name);
      } catch (Exception e) {
        ex = e;
        rethrowException(e);
      } finally {
        endFunction("get_partition_by_name", ret != null, ex, tbl_name);
      }
      return ret;
    }

    @Override
    public Partition append_partition_by_name(final String db_name, final String tbl_name,
        final String part_name) throws InvalidObjectException,
        AlreadyExistsException, MetaException, TException {
      return append_partition_by_name_with_environment_context(db_name, tbl_name, part_name, null);
    }

    @Override
    public Partition append_partition_by_name_with_environment_context(final String db_name,
        final String tbl_name, final String part_name, final EnvironmentContext env_context)
        throws InvalidObjectException, AlreadyExistsException, MetaException, TException {
      startFunction("append_partition_by_name", ": db=" + db_name + " tbl="
          + tbl_name + " part=" + part_name);

      Partition ret = null;
      Exception ex = null;
      try {
        RawStore ms = getMS();
        List<String> partVals = getPartValsFromName(ms, db_name, tbl_name, part_name);
        ret = append_partition_common(ms, db_name, tbl_name, partVals, env_context);
      } catch (Exception e) {
        ex = e;
        if (e instanceof InvalidObjectException) {
          throw (InvalidObjectException) e;
        } else if (e instanceof AlreadyExistsException) {
          throw (AlreadyExistsException) e;
        } else if (e instanceof MetaException) {
          throw (MetaException) e;
        } else if (e instanceof TException) {
          throw (TException) e;
        } else {
          throw newMetaException(e);
        }
      } finally {
        endFunction("append_partition_by_name", ret != null, ex, tbl_name);
      }
      return ret;
    }

    private boolean drop_partition_by_name_core(final RawStore ms, final String db_name,
        final String tbl_name, final String part_name, final boolean deleteData,
        final EnvironmentContext envContext) throws NoSuchObjectException, MetaException,
        TException, IOException, InvalidObjectException, InvalidInputException {

      List<String> partVals = null;
      try {
        partVals = getPartValsFromName(ms, db_name, tbl_name, part_name);
      } catch (InvalidObjectException e) {
        throw new NoSuchObjectException(e.getMessage());
      }

      return drop_partition_common(ms, db_name, tbl_name, partVals, deleteData, envContext);
    }

    @Override
    public boolean drop_partition_by_name(final String db_name, final String tbl_name,
        final String part_name, final boolean deleteData) throws NoSuchObjectException,
        MetaException, TException {
      return drop_partition_by_name_with_environment_context(db_name, tbl_name, part_name,
          deleteData, null);
    }

    @Override
    public boolean drop_partition_by_name_with_environment_context(final String db_name,
        final String tbl_name, final String part_name, final boolean deleteData,
        final EnvironmentContext envContext) throws NoSuchObjectException,
        MetaException, TException {
      startFunction("drop_partition_by_name", ": db=" + db_name + " tbl="
          + tbl_name + " part=" + part_name);

      boolean ret = false;
      Exception ex = null;
      try {
        ret = drop_partition_by_name_core(getMS(), db_name, tbl_name,
            part_name, deleteData, envContext);
      } catch (IOException e) {
        ex = e;
        throw new MetaException(e.getMessage());
      } catch (Exception e) {
        ex = e;
        rethrowException(e);
      } finally {
        endFunction("drop_partition_by_name", ret, ex, tbl_name);
      }

      return ret;
    }

    @Override
    public List<Partition> get_partitions_ps(final String db_name,
        final String tbl_name, final List<String> part_vals,
        final short max_parts) throws MetaException, TException, NoSuchObjectException {
      startPartitionFunction("get_partitions_ps", db_name, tbl_name, part_vals);

      List<Partition> ret = null;
      Exception ex = null;
      try {
        ret = get_partitions_ps_with_auth(db_name, tbl_name, part_vals,
            max_parts, null, null);
      } catch (Exception e) {
        ex = e;
        rethrowException(e);
      } finally {
        endFunction("get_partitions_ps", ret != null, ex, tbl_name);
      }

      return ret;
    }

    @Override
    public List<Partition> get_partitions_ps_with_auth(final String db_name,
        final String tbl_name, final List<String> part_vals,
        final short max_parts, final String userName,
        final List<String> groupNames) throws MetaException, TException, NoSuchObjectException {
      startPartitionFunction("get_partitions_ps_with_auth", db_name, tbl_name,
          part_vals);
      fireReadTablePreEvent(db_name, tbl_name);
      List<Partition> ret = null;
      Exception ex = null;
      try {
        ret = getMS().listPartitionsPsWithAuth(db_name, tbl_name, part_vals, max_parts,
            userName, groupNames);
      } catch (InvalidObjectException e) {
        ex = e;
        throw new MetaException(e.getMessage());
      } catch (Exception e) {
        ex = e;
        rethrowException(e);
      } finally {
        endFunction("get_partitions_ps_with_auth", ret != null, ex, tbl_name);
      }
      return ret;
    }

    @Override
    public List<String> get_partition_names_ps(final String db_name,
        final String tbl_name, final List<String> part_vals, final short max_parts)
        throws MetaException, TException, NoSuchObjectException {
      startPartitionFunction("get_partitions_names_ps", db_name, tbl_name, part_vals);
      fireReadTablePreEvent(db_name, tbl_name);
      List<String> ret = null;
      Exception ex = null;
      try {
        ret = getMS().listPartitionNamesPs(db_name, tbl_name, part_vals, max_parts);
      } catch (Exception e) {
        ex = e;
        rethrowException(e);
      } finally {
        endFunction("get_partitions_names_ps", ret != null, ex, tbl_name);
      }
      return ret;
    }

    @Override
    public List<String> partition_name_to_vals(String part_name)
        throws MetaException, TException {
      if (part_name.length() == 0) {
        return new ArrayList<String>();
      }
      LinkedHashMap<String, String> map = Warehouse.makeSpecFromName(part_name);
      List<String> part_vals = new ArrayList<String>();
      part_vals.addAll(map.values());
      return part_vals;
    }

    @Override
    public Map<String, String> partition_name_to_spec(String part_name) throws MetaException,
        TException {
      if (part_name.length() == 0) {
        return new HashMap<String, String>();
      }
      return Warehouse.makeSpecFromName(part_name);
    }

    @Override
    public Index add_index(final Index newIndex, final Table indexTable)
        throws InvalidObjectException, AlreadyExistsException, MetaException, TException {
      startFunction("add_index", ": " + newIndex.toString() + " " + indexTable.toString());
      Index ret = null;
      Exception ex = null;
      try {
        ret = add_index_core(getMS(), newIndex, indexTable);
      } catch (Exception e) {
        ex = e;
        if (e instanceof InvalidObjectException) {
          throw (InvalidObjectException) e;
        } else if (e instanceof AlreadyExistsException) {
          throw (AlreadyExistsException) e;
        } else if (e instanceof MetaException) {
          throw (MetaException) e;
        } else if (e instanceof TException) {
          throw (TException) e;
        } else {
          throw newMetaException(e);
        }
      } finally {
        String tableName = indexTable != null ? indexTable.getTableName() : null;
        endFunction("add_index", ret != null, ex, tableName);
      }
      return ret;
    }

    private Index add_index_core(final RawStore ms, final Index index, final Table indexTable)
        throws InvalidObjectException, AlreadyExistsException, MetaException {

      boolean success = false, indexTableCreated = false;

      String[] qualified =
          MetaStoreUtils.getQualifiedName(index.getDbName(), index.getIndexTableName());

      try {
        ms.openTransaction();
        firePreEvent(new PreAddIndexEvent(index, this));

        Index old_index = null;
        try {
          old_index = get_index_by_name(index.getDbName(), index
              .getOrigTableName(), index.getIndexName());
        } catch (Exception e) {
        }
        if (old_index != null) {
          throw new AlreadyExistsException("Index already exists:" + index);
        }
        Table origTbl = ms.getTable(index.getDbName(), index.getOrigTableName());
        if (origTbl == null) {
          throw new InvalidObjectException(
              "Unable to add index because database or the orginal table do not exist");
        }

        // set create time
        long time = System.currentTimeMillis() / 1000;
        Table indexTbl = indexTable;
        if (indexTbl != null) {
          try {
            indexTbl = ms.getTable(qualified[0], qualified[1]);
          } catch (Exception e) {
          }
          if (indexTbl != null) {
            throw new InvalidObjectException(
                "Unable to add index because index table already exists");
          }
          this.create_table(indexTable);
          indexTableCreated = true;
        }

        index.setCreateTime((int) time);
        index.putToParameters(hive_metastoreConstants.DDL_TIME, Long.toString(time));

        ms.addIndex(index);
        success = ms.commitTransaction();
        return index;
      } finally {
        if (!success) {
          if (indexTableCreated) {
            try {
              drop_table(qualified[0], qualified[1], false);
            } catch (Exception e) {
            }
          }
          ms.rollbackTransaction();
        }
        for (MetaStoreEventListener listener : listeners) {
          AddIndexEvent addIndexEvent = new AddIndexEvent(index, success, this);
          listener.onAddIndex(addIndexEvent);
        }
      }
    }

    @Override
    public boolean drop_index_by_name(final String dbName, final String tblName,
        final String indexName, final boolean deleteData) throws NoSuchObjectException,
        MetaException, TException {
      startFunction("drop_index_by_name", ": db=" + dbName + " tbl="
          + tblName + " index=" + indexName);

      boolean ret = false;
      Exception ex = null;
      try {
        ret = drop_index_by_name_core(getMS(), dbName, tblName,
            indexName, deleteData);
      } catch (IOException e) {
        ex = e;
        throw new MetaException(e.getMessage());
      } catch (Exception e) {
        ex = e;
        rethrowException(e);
      } finally {
        endFunction("drop_index_by_name", ret, ex, tblName);
      }

      return ret;
    }

    private boolean drop_index_by_name_core(final RawStore ms,
        final String dbName, final String tblName,
        final String indexName, final boolean deleteData) throws NoSuchObjectException,
        MetaException, TException, IOException, InvalidObjectException, InvalidInputException {

      boolean success = false;
      Index index = null;
      Path tblPath = null;
      List<Path> partPaths = null;
      try {
        ms.openTransaction();

        // drop the underlying index table
        index = get_index_by_name(dbName, tblName, indexName);  // throws exception if not exists

        firePreEvent(new PreDropIndexEvent(index, this));

        ms.dropIndex(dbName, tblName, indexName);

        String idxTblName = index.getIndexTableName();
        if (idxTblName != null) {
          String[] qualified = MetaStoreUtils.getQualifiedName(index.getDbName(), idxTblName);
          Table tbl = get_table_core(qualified[0], qualified[1]);
          if (tbl.getSd() == null) {
            throw new MetaException("Table metadata is corrupted");
          }

          if (tbl.getSd().getLocation() != null) {
            tblPath = new Path(tbl.getSd().getLocation());
            if (!wh.isWritable(tblPath.getParent())) {
              throw new MetaException("Index table metadata not deleted since " +
                  tblPath.getParent() + " is not writable by " +
                  hiveConf.getUser());
            }
          }

          // Drop the partitions and get a list of partition locations which need to be deleted
          partPaths = dropPartitionsAndGetLocations(ms, qualified[0], qualified[1], tblPath,
              tbl.getPartitionKeys(), deleteData);

          if (!ms.dropTable(qualified[0], qualified[1])) {
            throw new MetaException("Unable to drop underlying data table "
                + qualified[0] + "." + qualified[1] + " for index " + indexName);
          }
        }
        success = ms.commitTransaction();
      } finally {
        if (!success) {
          ms.rollbackTransaction();
        } else if (deleteData && tblPath != null) {
          deletePartitionData(partPaths);
          deleteTableData(tblPath);
          // ok even if the data is not deleted
        }
        for (MetaStoreEventListener listener : listeners) {
          DropIndexEvent dropIndexEvent = new DropIndexEvent(index, success, this);
          listener.onDropIndex(dropIndexEvent);
        }
      }
      return success;
    }

    @Override
    public Index get_index_by_name(final String dbName, final String tblName,
        final String indexName) throws MetaException, NoSuchObjectException,
        TException {

      startFunction("get_index_by_name", ": db=" + dbName + " tbl="
          + tblName + " index=" + indexName);

      Index ret = null;
      Exception ex = null;
      try {
        ret = get_index_by_name_core(getMS(), dbName, tblName, indexName);
      } catch (Exception e) {
        ex = e;
        rethrowException(e);
      } finally {
        endFunction("get_index_by_name", ret != null, ex, tblName);
      }
      return ret;
    }

    private Index get_index_by_name_core(final RawStore ms, final String db_name,
        final String tbl_name, final String index_name)
        throws MetaException, NoSuchObjectException, TException {
      Index index = ms.getIndex(db_name, tbl_name, index_name);

      if (index == null) {
        throw new NoSuchObjectException(db_name + "." + tbl_name
            + " index=" + index_name + " not found");
      }
      return index;
    }

    @Override
    public List<String> get_index_names(final String dbName, final String tblName,
        final short maxIndexes) throws MetaException, TException {
      startTableFunction("get_index_names", dbName, tblName);

      List<String> ret = null;
      Exception ex = null;
      try {
        ret = getMS().listIndexNames(dbName, tblName, maxIndexes);
      } catch (Exception e) {
        ex = e;
        if (e instanceof MetaException) {
          throw (MetaException) e;
        } else if (e instanceof TException) {
          throw (TException) e;
        } else {
          throw newMetaException(e);
        }
      } finally {
        endFunction("get_index_names", ret != null, ex, tblName);
      }
      return ret;
    }

    @Override
    public List<Index> get_indexes(final String dbName, final String tblName,
        final short maxIndexes) throws NoSuchObjectException, MetaException,
        TException {
      startTableFunction("get_indexes", dbName, tblName);

      List<Index> ret = null;
      Exception ex = null;
      try {
        ret = getMS().getIndexes(dbName, tblName, maxIndexes);
      } catch (Exception e) {
        ex = e;
        rethrowException(e);
      } finally {
        endFunction("get_indexes", ret != null, ex, tblName);
      }
      return ret;
    }

    private String lowerCaseConvertPartName(String partName) throws MetaException {
      boolean isFirst = true;
      Map<String, String> partSpec = Warehouse.makeEscSpecFromName(partName);
      String convertedPartName = new String();

      for (Map.Entry<String, String> entry : partSpec.entrySet()) {
        String partColName = entry.getKey();
        String partColVal = entry.getValue();

        if (!isFirst) {
          convertedPartName += "/";
        } else {
          isFirst = false;
        }
        convertedPartName += partColName.toLowerCase() + "=" + partColVal;
      }
      return convertedPartName;
    }

    @Override
    public ColumnStatistics get_table_column_statistics(String dbName, String tableName,
      String colName) throws NoSuchObjectException, MetaException, TException,
      InvalidInputException, InvalidObjectException
    {
      dbName = dbName.toLowerCase();
      tableName = tableName.toLowerCase();
      colName = colName.toLowerCase();
      startFunction("get_column_statistics_by_table: db=" + dbName + " table=" + tableName +
                    " column=" + colName);
      ColumnStatistics statsObj = null;
      try {
        statsObj = getMS().getTableColumnStatistics(
            dbName, tableName, Lists.newArrayList(colName));
        assert statsObj.getStatsObjSize() <= 1;
        return statsObj;
      } finally {
        endFunction("get_column_statistics_by_table: ", statsObj != null, null, tableName);
      }
    }

    @Override
    public TableStatsResult get_table_statistics_req(TableStatsRequest request)
        throws MetaException, NoSuchObjectException, TException {
      String dbName = request.getDbName().toLowerCase();
      String tblName = request.getTblName().toLowerCase();
      startFunction("get_table_statistics_req: db=" + dbName + " table=" + tblName);
      TableStatsResult result = null;
      List<String> lowerCaseColNames = new ArrayList<String>(request.getColNames().size());
      for (String colName : request.getColNames()) {
        lowerCaseColNames.add(colName.toLowerCase());
      }
      try {
        ColumnStatistics cs = getMS().getTableColumnStatistics(dbName, tblName, lowerCaseColNames);
        result = new TableStatsResult(
            cs == null ? Lists.<ColumnStatisticsObj>newArrayList() : cs.getStatsObj());
      } finally {
        endFunction("get_table_statistics_req: ", result == null, null, tblName);
      }
      return result;
    }

    @Override
    public ColumnStatistics get_partition_column_statistics(String dbName, String tableName,
      String partName, String colName) throws NoSuchObjectException, MetaException,
      InvalidInputException, TException, InvalidObjectException {
      dbName = dbName.toLowerCase();
      tableName = tableName.toLowerCase();
      colName = colName.toLowerCase();
      String convertedPartName = lowerCaseConvertPartName(partName);
      startFunction("get_column_statistics_by_partition: db=" + dbName + " table=" + tableName +
          " partition=" + convertedPartName + " column=" + colName);
      ColumnStatistics statsObj = null;

      try {
        List<ColumnStatistics> list = getMS().getPartitionColumnStatistics(dbName, tableName,
            Lists.newArrayList(convertedPartName), Lists.newArrayList(colName));
        if (list.isEmpty()) return null;
        if (list.size() != 1) {
          throw new MetaException(list.size() + " statistics for single column and partition");
        }
        statsObj = list.get(0);
      } finally {
        endFunction("get_column_statistics_by_partition: ", statsObj != null, null, tableName);
      }
      return statsObj;
    }

    @Override
    public PartitionsStatsResult get_partitions_statistics_req(PartitionsStatsRequest request)
        throws MetaException, NoSuchObjectException, TException {
      String dbName = request.getDbName().toLowerCase();
      String tblName = request.getTblName().toLowerCase();
      startFunction("get_partitions_statistics_req: db=" + dbName + " table=" + tblName);

      PartitionsStatsResult result = null;
      List<String> lowerCaseColNames = new ArrayList<String>(request.getColNames().size());
      for (String colName : request.getColNames()) {
        lowerCaseColNames.add(colName.toLowerCase());
      }
      List<String> lowerCasePartNames = new ArrayList<String>(request.getPartNames().size());
      for (String partName : request.getPartNames()) {
        lowerCasePartNames.add(lowerCaseConvertPartName(partName));
      }
      try {
        List<ColumnStatistics> stats = getMS().getPartitionColumnStatistics(
            dbName, tblName, lowerCasePartNames, lowerCaseColNames);
        Map<String, List<ColumnStatisticsObj>> map =
            new HashMap<String, List<ColumnStatisticsObj>>();
        for (ColumnStatistics stat : stats) {
          map.put(stat.getStatsDesc().getPartName(), stat.getStatsObj());
        }
        result = new PartitionsStatsResult(map);
      } finally {
        endFunction("get_partitions_statistics_req: ", result == null, null, tblName);
      }
      return result;
    }

    @Override
    public boolean update_table_column_statistics(ColumnStatistics colStats)
      throws NoSuchObjectException,InvalidObjectException,MetaException,TException,
      InvalidInputException
    {
      String dbName = null;
      String tableName = null;
      String colName = null;
      ColumnStatisticsDesc statsDesc = colStats.getStatsDesc();
      dbName = statsDesc.getDbName().toLowerCase();
      tableName = statsDesc.getTableName().toLowerCase();

      statsDesc.setDbName(dbName);
      statsDesc.setTableName(tableName);
      long time = System.currentTimeMillis() / 1000;
      statsDesc.setLastAnalyzed(time);

      List<ColumnStatisticsObj> statsObjs =  colStats.getStatsObj();

      for (ColumnStatisticsObj statsObj:statsObjs) {
        colName = statsObj.getColName().toLowerCase();
        statsObj.setColName(colName);
        statsObj.setColType(statsObj.getColType().toLowerCase());
        startFunction("write_column_statistics:  db=" + dbName + " table=" + tableName +
          " column=" + colName);
      }

     colStats.setStatsDesc(statsDesc);
     colStats.setStatsObj(statsObjs);

     boolean ret = false;

      try {
        ret = getMS().updateTableColumnStatistics(colStats);
        return ret;
      } finally {
        endFunction("write_column_statistics: ", ret != false, null, tableName);
      }
    }

    @Override
    public boolean update_partition_column_statistics(ColumnStatistics colStats)
      throws NoSuchObjectException,InvalidObjectException,MetaException,TException,
      InvalidInputException
    {

      String dbName = null;
      String tableName = null;
      String partName = null;
      String colName = null;

      ColumnStatisticsDesc statsDesc = colStats.getStatsDesc();
      dbName = statsDesc.getDbName().toLowerCase();
      tableName = statsDesc.getTableName().toLowerCase();
      partName = lowerCaseConvertPartName(statsDesc.getPartName());

      statsDesc.setDbName(dbName);
      statsDesc.setTableName(tableName);
      statsDesc.setPartName(partName);

      long time = System.currentTimeMillis() / 1000;
      statsDesc.setLastAnalyzed(time);

      List<ColumnStatisticsObj> statsObjs =  colStats.getStatsObj();

      for (ColumnStatisticsObj statsObj:statsObjs) {
        colName = statsObj.getColName().toLowerCase();
        statsObj.setColName(colName);
        statsObj.setColType(statsObj.getColType().toLowerCase());
        startFunction("write_partition_column_statistics:  db=" + dbName + " table=" + tableName +
          " part=" + partName + "column=" + colName);
      }

      colStats.setStatsDesc(statsDesc);
      colStats.setStatsObj(statsObjs);

      boolean ret = false;

      try {
        List<String> partVals = getPartValsFromName(getMS(), dbName,
            tableName, partName);
        ret = getMS().updatePartitionColumnStatistics(colStats, partVals);
        return ret;
      } finally {
        endFunction("write_partition_column_statistics: ", ret != false, null, tableName);
      }
    }

    @Override
    public boolean delete_partition_column_statistics(String dbName, String tableName,
      String partName, String colName) throws NoSuchObjectException, MetaException,
      InvalidObjectException, TException, InvalidInputException
    {
      dbName = dbName.toLowerCase();
      tableName = tableName.toLowerCase();
      if (colName != null) {
        colName = colName.toLowerCase();
      }
      String convertedPartName = lowerCaseConvertPartName(partName);
      startFunction("delete_column_statistics_by_partition: db=" + dbName + " table=" + tableName +
                    " partition=" + convertedPartName + " column=" + colName);
      boolean ret = false;

      try {
        List<String> partVals = getPartValsFromName(getMS(), dbName, tableName, convertedPartName);
        ret = getMS().deletePartitionColumnStatistics(dbName, tableName,
                                                      convertedPartName, partVals, colName);
      } finally {
        endFunction("delete_column_statistics_by_partition: ", ret != false, null, tableName);
      }
      return ret;
    }

    @Override
    public boolean delete_table_column_statistics(String dbName, String tableName, String colName)
      throws NoSuchObjectException, MetaException, InvalidObjectException, TException,
      InvalidInputException
   {
      dbName = dbName.toLowerCase();
      tableName = tableName.toLowerCase();

      if (colName != null) {
        colName = colName.toLowerCase();
      }
      startFunction("delete_column_statistics_by_table: db=" + dbName + " table=" + tableName +
                    " column=" + colName);

      boolean ret = false;
      try {
        ret = getMS().deleteTableColumnStatistics(dbName, tableName, colName);
      } finally {
        endFunction("delete_column_statistics_by_table: ", ret != false, null, tableName);
      }
      return ret;
   }

    @Override
    public List<Partition> get_partitions_by_filter(final String dbName,
        final String tblName, final String filter, final short maxParts)
        throws MetaException, NoSuchObjectException, TException {
      startTableFunction("get_partitions_by_filter", dbName, tblName);
      fireReadTablePreEvent(dbName, tblName);
      List<Partition> ret = null;
      Exception ex = null;
      try {
        checkLimitNumberOfPartitionsByFilter(dbName, tblName, filter, maxParts);
        ret = getMS().getPartitionsByFilter(dbName, tblName, filter, maxParts);
      } catch (Exception e) {
        ex = e;
        rethrowException(e);
      } finally {
        endFunction("get_partitions_by_filter", ret != null, ex, tblName);
      }
      return ret;
    }

    @Override
    public List<PartitionSpec> get_part_specs_by_filter(final String dbName,
        final String tblName, final String filter, final int maxParts)
        throws MetaException, NoSuchObjectException, TException {

      startTableFunction("get_partitions_by_filter_pspec", dbName, tblName);

      List<PartitionSpec> partitionSpecs = null;
      try {
        Table table = get_table_core(dbName, tblName);
        List<Partition> partitions = get_partitions_by_filter(dbName, tblName, filter, (short) maxParts);

        if (is_partition_spec_grouping_enabled(table)) {
          partitionSpecs = get_partitionspecs_grouped_by_storage_descriptor(table, partitions);
        }
        else {
          PartitionSpec pSpec = new PartitionSpec();
          pSpec.setPartitionList(new PartitionListComposingSpec(partitions));
          pSpec.setRootPath(table.getSd().getLocation());
          pSpec.setDbName(dbName);
          pSpec.setTableName(tblName);
          partitionSpecs = Arrays.asList(pSpec);
        }

        return partitionSpecs;
      }
      finally {
        endFunction("get_partitions_by_filter_pspec", partitionSpecs != null && !partitionSpecs.isEmpty(), null, tblName);
      }
    }

    @Override
    public PartitionsByExprResult get_partitions_by_expr(
        PartitionsByExprRequest req) throws TException {
      String dbName = req.getDbName(), tblName = req.getTblName();
      startTableFunction("get_partitions_by_expr", dbName, tblName);
      fireReadTablePreEvent(dbName, tblName);
      PartitionsByExprResult ret = null;
      Exception ex = null;
      try {
        checkLimitNumberOfPartitionsByExpr(dbName, tblName, req.getExpr(), UNLIMITED_MAX_PARTITIONS);
        List<Partition> partitions = new LinkedList<Partition>();
        boolean hasUnknownPartitions = getMS().getPartitionsByExpr(dbName, tblName,
            req.getExpr(), req.getDefaultPartitionName(), req.getMaxParts(), partitions);
        ret = new PartitionsByExprResult(partitions, hasUnknownPartitions);
      } catch (Exception e) {
        ex = e;
        rethrowException(e);
      } finally {
        endFunction("get_partitions_by_expr", ret != null, ex, tblName);
      }
      return ret;
    }

    private void rethrowException(Exception e)
        throws MetaException, NoSuchObjectException, TException {
      // TODO: Both of these are TException, why do we need these separate clauses?
      if (e instanceof MetaException) {
        throw (MetaException) e;
      } else if (e instanceof NoSuchObjectException) {
        throw (NoSuchObjectException) e;
      } else if (e instanceof TException) {
        throw (TException) e;
      } else {
        throw newMetaException(e);
      }
    }
    
    public int get_num_partitions_by_filter(final String dbName,
                                            final String tblName, final String filter)
            throws TException {
      startTableFunction("get_num_partitions_by_filter", dbName, tblName);

      int ret = -1;
      Exception ex = null;
      try {
        ret = getMS().getNumPartitionsByFilter(dbName, tblName, filter);
      } catch (Exception e) {
        ex = e;
        rethrowException(e);
      } finally {
        endFunction("get_num_partitions_by_filter", ret != -1, ex, tblName);
      }
      return ret;
    }

    public int get_num_partitions_by_expr(final String dbName,
                                            final String tblName, final byte[] expr)
        throws TException {
      startTableFunction("get_num_partitions_by_expr", dbName, tblName);

      int ret = -1;
      Exception ex = null;
      try {
        ret = getMS().getNumPartitionsByExpr(dbName, tblName, expr);
      } catch (Exception e) {
        ex = e;
        rethrowException(e);
      } finally {
        endFunction("get_num_partitions_by_expr", ret != -1, ex, tblName);
      }
      return ret;
    }

    @Override
    public List<Partition> get_partitions_by_names(final String dbName,
        final String tblName, final List<String> partNames)
        throws MetaException, NoSuchObjectException, TException {

      startTableFunction("get_partitions_by_names", dbName, tblName);
      fireReadTablePreEvent(dbName, tblName);
      List<Partition> ret = null;
      Exception ex = null;
      try {
        ret = getMS().getPartitionsByNames(dbName, tblName, partNames);
      } catch (Exception e) {
        ex = e;
        rethrowException(e);
      } finally {
        endFunction("get_partitions_by_names", ret != null, ex, tblName);
      }
      return ret;
    }

    @Override
    public PrincipalPrivilegeSet get_privilege_set(HiveObjectRef hiveObject,
        String userName, List<String> groupNames) throws MetaException,
        TException {
      firePreEvent(new PreAuthorizationCallEvent(this));
      if (hiveObject.getObjectType() == HiveObjectType.COLUMN) {
        String partName = getPartName(hiveObject);
        return this.get_column_privilege_set(hiveObject.getDbName(), hiveObject
            .getObjectName(), partName, hiveObject.getColumnName(), userName,
            groupNames);
      } else if (hiveObject.getObjectType() == HiveObjectType.PARTITION) {
        String partName = getPartName(hiveObject);
        return this.get_partition_privilege_set(hiveObject.getDbName(),
            hiveObject.getObjectName(), partName, userName, groupNames);
      } else if (hiveObject.getObjectType() == HiveObjectType.DATABASE) {
        return this.get_db_privilege_set(hiveObject.getDbName(), userName,
            groupNames);
      } else if (hiveObject.getObjectType() == HiveObjectType.TABLE) {
        return this.get_table_privilege_set(hiveObject.getDbName(), hiveObject
            .getObjectName(), userName, groupNames);
      } else if (hiveObject.getObjectType() == HiveObjectType.GLOBAL) {
        return this.get_user_privilege_set(userName, groupNames);
      }
      return null;
    }

    private String getPartName(HiveObjectRef hiveObject) throws MetaException {
      String partName = null;
      List<String> partValue = hiveObject.getPartValues();
      if (partValue != null && partValue.size() > 0) {
        try {
          Table table = get_table_core(hiveObject.getDbName(), hiveObject
              .getObjectName());
          partName = Warehouse
              .makePartName(table.getPartitionKeys(), partValue);
        } catch (NoSuchObjectException e) {
          throw new MetaException(e.getMessage());
        }
      }
      return partName;
    }

    private PrincipalPrivilegeSet get_column_privilege_set(final String dbName,
        final String tableName, final String partName, final String columnName,
        final String userName, final List<String> groupNames) throws MetaException,
        TException {
      incrementCounter("get_column_privilege_set");

      PrincipalPrivilegeSet ret = null;
      try {
        ret = getMS().getColumnPrivilegeSet(
            dbName, tableName, partName, columnName, userName, groupNames);
      } catch (MetaException e) {
        throw e;
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
      return ret;
    }

    private PrincipalPrivilegeSet get_db_privilege_set(final String dbName,
        final String userName, final List<String> groupNames) throws MetaException,
        TException {
      incrementCounter("get_db_privilege_set");

      PrincipalPrivilegeSet ret = null;
      try {
        ret = getMS().getDBPrivilegeSet(dbName, userName, groupNames);
      } catch (MetaException e) {
        throw e;
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
      return ret;
    }

    private PrincipalPrivilegeSet get_partition_privilege_set(
        final String dbName, final String tableName, final String partName,
        final String userName, final List<String> groupNames)
        throws MetaException, TException {
      incrementCounter("get_partition_privilege_set");

      PrincipalPrivilegeSet ret = null;
      try {
        ret = getMS().getPartitionPrivilegeSet(dbName, tableName, partName,
            userName, groupNames);
      } catch (MetaException e) {
        throw e;
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
      return ret;
    }

    private PrincipalPrivilegeSet get_table_privilege_set(final String dbName,
        final String tableName, final String userName,
        final List<String> groupNames) throws MetaException, TException {
      incrementCounter("get_table_privilege_set");

      PrincipalPrivilegeSet ret = null;
      try {
        ret = getMS().getTablePrivilegeSet(dbName, tableName, userName,
            groupNames);
      } catch (MetaException e) {
        throw e;
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
      return ret;
    }

    @Override
    public boolean grant_role(final String roleName,
        final String principalName, final PrincipalType principalType,
        final String grantor, final PrincipalType grantorType, final boolean grantOption)
        throws MetaException, TException {
      incrementCounter("add_role_member");
      firePreEvent(new PreAuthorizationCallEvent(this));
      if (PUBLIC.equals(roleName)) {
        throw new MetaException("No user can be added to " + PUBLIC +". Since all users implictly"
        + " belong to " + PUBLIC + " role.");
      }
      Boolean ret = null;
      try {
        RawStore ms = getMS();
        Role role = ms.getRole(roleName);
        if(principalType == PrincipalType.ROLE){
          //check if this grant statement will end up creating a cycle
          if(isNewRoleAParent(principalName, roleName)){
            throw new MetaException("Cannot grant role " + principalName + " to " + roleName +
                " as " + roleName + " already belongs to the role " + principalName +
                ". (no cycles allowed)");
          }
        }
        ret = ms.grantRole(role, principalName, principalType, grantor, grantorType, grantOption);
      } catch (MetaException e) {
        throw e;
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
      return ret;
    }



    /**
     * Check if newRole is in parent hierarchy of curRole
     * @param newRole
     * @param curRole
     * @return true if newRole is curRole or present in its hierarchy
     * @throws MetaException
     */
    private boolean isNewRoleAParent(String newRole, String curRole) throws MetaException {
      if(newRole.equals(curRole)){
        return true;
      }
      //do this check recursively on all the parent roles of curRole
      List<MRoleMap> parentRoleMaps = getMS().listRoles(curRole, PrincipalType.ROLE);
      for(MRoleMap parentRole : parentRoleMaps){
        if(isNewRoleAParent(newRole, parentRole.getRole().getRoleName())){
          return true;
        }
      }
      return false;
    }

    @Override
    public List<Role> list_roles(final String principalName,
        final PrincipalType principalType) throws MetaException, TException {
      incrementCounter("list_roles");
      firePreEvent(new PreAuthorizationCallEvent(this));
      List<Role> result = new ArrayList<Role>();
      try {
        List<MRoleMap> roleMaps = getMS().listRoles(principalName, principalType);
        if (roleMaps != null) {
          for (MRoleMap roleMap : roleMaps) {
            MRole mrole = roleMap.getRole();
            Role role = new Role(mrole.getRoleName(), mrole.getCreateTime(), mrole.getOwnerName());
            result.add(role);
          }
        }
        return result;
      } catch (MetaException e) {
        throw e;
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    }



    @Override
    public boolean create_role(final Role role)
        throws MetaException, TException {
      incrementCounter("create_role");
      firePreEvent(new PreAuthorizationCallEvent(this));
      if (PUBLIC.equals(role.getRoleName())) {
         throw new MetaException(PUBLIC + " role implictly exists. It can't be created.");
      }
      Boolean ret = null;
      try {
        ret = getMS().addRole(role.getRoleName(), role.getOwnerName());
      } catch (MetaException e) {
        throw e;
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
      return ret;
    }

    @Override
    public boolean drop_role(final String roleName)
        throws MetaException, TException {
      incrementCounter("drop_role");
      firePreEvent(new PreAuthorizationCallEvent(this));
      if (ADMIN.equals(roleName) || PUBLIC.equals(roleName)) {
        throw new MetaException(PUBLIC + "," + ADMIN + " roles can't be dropped.");
      }
      Boolean ret = null;
      try {
        ret = getMS().removeRole(roleName);
      } catch (MetaException e) {
        throw e;
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
      return ret;
    }

    @Override
    public List<String> get_role_names() throws MetaException, TException {
      incrementCounter("get_role_names");
      firePreEvent(new PreAuthorizationCallEvent(this));
      List<String> ret = null;
      try {
        ret = getMS().listRoleNames();
        return ret;
      } catch (MetaException e) {
        throw e;
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    }

    @Override
    public boolean grant_privileges(final PrivilegeBag privileges) throws MetaException,
        TException {
      incrementCounter("grant_privileges");
      firePreEvent(new PreAuthorizationCallEvent(this));
      Boolean ret = null;
      try {
        ret = getMS().grantPrivileges(privileges);
      } catch (MetaException e) {
        throw e;
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
      return ret;
    }

    @Override
    public boolean revoke_role(final String roleName, final String userName,
        final PrincipalType principalType) throws MetaException, TException {
      return revoke_role(roleName, userName, principalType, false);
    }

    private boolean revoke_role(final String roleName, final String userName,
        final PrincipalType principalType, boolean grantOption) throws MetaException, TException {
      incrementCounter("remove_role_member");
      firePreEvent(new PreAuthorizationCallEvent(this));
      if (PUBLIC.equals(roleName)) {
        throw new MetaException(PUBLIC + " role can't be revoked.");
      }
      Boolean ret = null;
      try {
        RawStore ms = getMS();
        Role mRole = ms.getRole(roleName);
        ret = ms.revokeRole(mRole, userName, principalType, grantOption);
      } catch (MetaException e) {
        throw e;
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
      return ret;
    }

    @Override
    public GrantRevokeRoleResponse grant_revoke_role(GrantRevokeRoleRequest request)
        throws MetaException, org.apache.thrift.TException {
      GrantRevokeRoleResponse response = new GrantRevokeRoleResponse();
      boolean grantOption = false;
      if (request.isSetGrantOption()) {
        grantOption = request.isGrantOption();
      }
      switch (request.getRequestType()) {
        case GRANT: {
          boolean result = grant_role(request.getRoleName(),
              request.getPrincipalName(), request.getPrincipalType(),
              request.getGrantor(), request.getGrantorType(), grantOption);
          response.setSuccess(result);
          break;
        }
        case REVOKE: {
          boolean result = revoke_role(request.getRoleName(), request.getPrincipalName(),
              request.getPrincipalType(), grantOption);
          response.setSuccess(result);
          break;
        }
        default:
          throw new MetaException("Unknown request type " + request.getRequestType());
      }

      return response;
    }

    @Override
    public GrantRevokePrivilegeResponse grant_revoke_privileges(GrantRevokePrivilegeRequest request)
        throws MetaException, org.apache.thrift.TException {
      GrantRevokePrivilegeResponse response = new GrantRevokePrivilegeResponse();
      switch (request.getRequestType()) {
        case GRANT: {
          boolean result = grant_privileges(request.getPrivileges());
          response.setSuccess(result);
          break;
        }
        case REVOKE: {
          boolean revokeGrantOption = false;
          if (request.isSetRevokeGrantOption()) {
            revokeGrantOption = request.isRevokeGrantOption();
          }
          boolean result = revoke_privileges(request.getPrivileges(), revokeGrantOption);
          response.setSuccess(result);
          break;
        }
        default:
          throw new MetaException("Unknown request type " + request.getRequestType());
      }

      return response;
    }

    @Override
    public boolean revoke_privileges(final PrivilegeBag privileges)
        throws MetaException, TException {
      return revoke_privileges(privileges, false);
    }

    public boolean revoke_privileges(final PrivilegeBag privileges, boolean grantOption)
        throws MetaException, TException {
      incrementCounter("revoke_privileges");
      firePreEvent(new PreAuthorizationCallEvent(this));
      Boolean ret = null;
      try {
        ret = getMS().revokePrivileges(privileges, grantOption);
      } catch (MetaException e) {
        throw e;
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
      return ret;
    }

    private PrincipalPrivilegeSet get_user_privilege_set(final String userName,
        final List<String> groupNames) throws MetaException, TException {
      incrementCounter("get_user_privilege_set");
      PrincipalPrivilegeSet ret = null;
      try {
        ret = getMS().getUserPrivilegeSet(userName, groupNames);
      } catch (MetaException e) {
        throw e;
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
      return ret;
    }

    @Override
    public List<HiveObjectPrivilege> list_privileges(String principalName,
        PrincipalType principalType, HiveObjectRef hiveObject)
        throws MetaException, TException {
      firePreEvent(new PreAuthorizationCallEvent(this));
      if (hiveObject.getObjectType() == null) {
        return getAllPrivileges(principalName, principalType);
      }
      if (hiveObject.getObjectType() == HiveObjectType.GLOBAL) {
        return list_global_privileges(principalName, principalType);
      }
      if (hiveObject.getObjectType() == HiveObjectType.DATABASE) {
        return list_db_privileges(principalName, principalType, hiveObject
            .getDbName());
      }
      if (hiveObject.getObjectType() == HiveObjectType.TABLE) {
        return list_table_privileges(principalName, principalType,
            hiveObject.getDbName(), hiveObject.getObjectName());
      }
      if (hiveObject.getObjectType() == HiveObjectType.PARTITION) {
        return list_partition_privileges(principalName, principalType,
            hiveObject.getDbName(), hiveObject.getObjectName(), hiveObject
            .getPartValues());
      }
      if (hiveObject.getObjectType() == HiveObjectType.COLUMN) {
        if (hiveObject.getPartValues() == null || hiveObject.getPartValues().isEmpty()) {
          return list_table_column_privileges(principalName, principalType,
              hiveObject.getDbName(), hiveObject.getObjectName(), hiveObject.getColumnName());
        }
        return list_partition_column_privileges(principalName, principalType,
            hiveObject.getDbName(), hiveObject.getObjectName(), hiveObject
            .getPartValues(), hiveObject.getColumnName());
      }
      return null;
    }

    private List<HiveObjectPrivilege> getAllPrivileges(String principalName,
        PrincipalType principalType) throws TException {
      List<HiveObjectPrivilege> privs = new ArrayList<HiveObjectPrivilege>();
      privs.addAll(list_global_privileges(principalName, principalType));
      privs.addAll(list_db_privileges(principalName, principalType, null));
      privs.addAll(list_table_privileges(principalName, principalType, null, null));
      privs.addAll(list_partition_privileges(principalName, principalType, null, null, null));
      privs.addAll(list_table_column_privileges(principalName, principalType, null, null, null));
      privs.addAll(list_partition_column_privileges(principalName, principalType,
          null, null, null, null));
      return privs;
    }

    private List<HiveObjectPrivilege> list_table_column_privileges(
        final String principalName, final PrincipalType principalType,
        final String dbName, final String tableName, final String columnName)
        throws MetaException, TException {
      incrementCounter("list_table_column_privileges");

      try {
        if (dbName == null) {
          return getMS().listPrincipalTableColumnGrantsAll(principalName, principalType);
        }
        if (principalName == null) {
          return getMS().listTableColumnGrantsAll(dbName, tableName, columnName);
        }
        List<MTableColumnPrivilege> mTableCols = getMS()
            .listPrincipalTableColumnGrants(principalName, principalType,
                dbName, tableName, columnName);
        if (mTableCols.isEmpty()) {
          return Collections.emptyList();
        }
        List<HiveObjectPrivilege> result = new ArrayList<HiveObjectPrivilege>();
        for (int i = 0; i < mTableCols.size(); i++) {
          MTableColumnPrivilege sCol = mTableCols.get(i);
          HiveObjectRef objectRef = new HiveObjectRef(
              HiveObjectType.COLUMN, dbName, tableName, null, sCol.getColumnName());
          HiveObjectPrivilege secObj = new HiveObjectPrivilege(
              objectRef, sCol.getPrincipalName(), principalType,
              new PrivilegeGrantInfo(sCol.getPrivilege(), sCol
                  .getCreateTime(), sCol.getGrantor(), PrincipalType
                  .valueOf(sCol.getGrantorType()), sCol
                  .getGrantOption()));
          result.add(secObj);
        }
        return result;
      } catch (MetaException e) {
        throw e;
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    }

    private List<HiveObjectPrivilege> list_partition_column_privileges(
        final String principalName, final PrincipalType principalType,
        final String dbName, final String tableName, final List<String> partValues,
        final String columnName) throws MetaException, TException {
      incrementCounter("list_partition_column_privileges");

      try {
        if (dbName == null) {
          return getMS().listPrincipalPartitionColumnGrantsAll(principalName, principalType);
        }
        Table tbl = get_table_core(dbName, tableName);
        String partName = Warehouse.makePartName(tbl.getPartitionKeys(), partValues);
        if (principalName == null) {
          return getMS().listPartitionColumnGrantsAll(dbName, tableName, partName, columnName);
        }
        List<MPartitionColumnPrivilege> mPartitionCols = getMS().listPrincipalPartitionColumnGrants(
            principalName,
            principalType, dbName, tableName, partName, columnName);
        if (mPartitionCols.isEmpty()) {
          return Collections.emptyList();
        }
        List<HiveObjectPrivilege> result = new ArrayList<HiveObjectPrivilege>();
        for (int i = 0; i < mPartitionCols.size(); i++) {
          MPartitionColumnPrivilege sCol = mPartitionCols.get(i);
          HiveObjectRef objectRef = new HiveObjectRef(
              HiveObjectType.COLUMN, dbName, tableName, partValues, sCol.getColumnName());
          HiveObjectPrivilege secObj = new HiveObjectPrivilege(objectRef,
              sCol.getPrincipalName(), principalType,
              new PrivilegeGrantInfo(sCol.getPrivilege(), sCol
                  .getCreateTime(), sCol.getGrantor(), PrincipalType
                  .valueOf(sCol.getGrantorType()), sCol.getGrantOption()));
          result.add(secObj);
        }
        return result;
      } catch (MetaException e) {
        throw e;
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    }

    private List<HiveObjectPrivilege> list_db_privileges(final String principalName,
        final PrincipalType principalType, final String dbName)
        throws MetaException, TException {
      incrementCounter("list_security_db_grant");

      try {
        if (dbName == null) {
          return getMS().listPrincipalDBGrantsAll(principalName, principalType);
        }
        if (principalName == null) {
          return getMS().listDBGrantsAll(dbName);
        }
        List<MDBPrivilege> mDbs = getMS().listPrincipalDBGrants(
            principalName, principalType, dbName);
        if (mDbs.isEmpty()) {
          return Collections.<HiveObjectPrivilege>emptyList();
        }
        List<HiveObjectPrivilege> result = new ArrayList<HiveObjectPrivilege>();
        for (int i = 0; i < mDbs.size(); i++) {
          MDBPrivilege sDB = mDbs.get(i);
          HiveObjectRef objectRef = new HiveObjectRef(
              HiveObjectType.DATABASE, dbName, null, null, null);
          HiveObjectPrivilege secObj = new HiveObjectPrivilege(objectRef,
              sDB.getPrincipalName(), principalType,
              new PrivilegeGrantInfo(sDB.getPrivilege(), sDB
                  .getCreateTime(), sDB.getGrantor(), PrincipalType
                  .valueOf(sDB.getGrantorType()), sDB.getGrantOption()));
          result.add(secObj);
        }
        return result;
      } catch (MetaException e) {
        throw e;
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    }

    private List<HiveObjectPrivilege> list_partition_privileges(
        final String principalName, final PrincipalType principalType,
        final String dbName, final String tableName, final List<String> partValues)
        throws MetaException, TException {
      incrementCounter("list_security_partition_grant");

      try {
        if (dbName == null) {
          return getMS().listPrincipalPartitionGrantsAll(principalName, principalType);
        }
        Table tbl = get_table_core(dbName, tableName);
        String partName = Warehouse.makePartName(tbl.getPartitionKeys(), partValues);
        if (principalName == null) {
          return getMS().listPartitionGrantsAll(dbName, tableName, partName);
        }
        List<MPartitionPrivilege> mParts = getMS().listPrincipalPartitionGrants(
            principalName, principalType, dbName, tableName, partName);
        if (mParts.isEmpty()) {
          return Collections.<HiveObjectPrivilege> emptyList();
        }
        List<HiveObjectPrivilege> result = new ArrayList<HiveObjectPrivilege>();
        for (int i = 0; i < mParts.size(); i++) {
          MPartitionPrivilege sPart = mParts.get(i);
          HiveObjectRef objectRef = new HiveObjectRef(
              HiveObjectType.PARTITION, dbName, tableName, partValues, null);
          HiveObjectPrivilege secObj = new HiveObjectPrivilege(objectRef,
              sPart.getPrincipalName(), principalType,
              new PrivilegeGrantInfo(sPart.getPrivilege(), sPart
                  .getCreateTime(), sPart.getGrantor(), PrincipalType
                  .valueOf(sPart.getGrantorType()), sPart
                  .getGrantOption()));

          result.add(secObj);
        }
        return result;
      } catch (MetaException e) {
        throw e;
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    }

    private List<HiveObjectPrivilege> list_table_privileges(
        final String principalName, final PrincipalType principalType,
        final String dbName, final String tableName) throws MetaException,
        TException {
      incrementCounter("list_security_table_grant");

      try {
        if (dbName == null) {
          return getMS().listPrincipalTableGrantsAll(principalName, principalType);
        }
        if (principalName == null) {
          return getMS().listTableGrantsAll(dbName, tableName);
        }
        List<MTablePrivilege> mTbls = getMS()
            .listAllTableGrants(principalName, principalType, dbName, tableName);
        if (mTbls.isEmpty()) {
          return Collections.<HiveObjectPrivilege> emptyList();
        }
        List<HiveObjectPrivilege> result = new ArrayList<HiveObjectPrivilege>();
        for (int i = 0; i < mTbls.size(); i++) {
          MTablePrivilege sTbl = mTbls.get(i);
          HiveObjectRef objectRef = new HiveObjectRef(
              HiveObjectType.TABLE, dbName, tableName, null, null);
          HiveObjectPrivilege secObj = new HiveObjectPrivilege(objectRef,
              sTbl.getPrincipalName(), principalType,
              new PrivilegeGrantInfo(sTbl.getPrivilege(), sTbl.getCreateTime(), sTbl
                  .getGrantor(), PrincipalType.valueOf(sTbl
                  .getGrantorType()), sTbl.getGrantOption()));
          result.add(secObj);
        }
        return result;
      } catch (MetaException e) {
        throw e;
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    }

    private List<HiveObjectPrivilege> list_global_privileges(
        final String principalName, final PrincipalType principalType)
        throws MetaException, TException {
      incrementCounter("list_security_user_grant");

      try {
        if (principalName == null) {
          return getMS().listGlobalGrantsAll();
        }
        List<MGlobalPrivilege> mUsers = getMS().listPrincipalGlobalGrants(
            principalName, principalType);
        if (mUsers.isEmpty()) {
          return Collections.<HiveObjectPrivilege> emptyList();
        }
        List<HiveObjectPrivilege> result = new ArrayList<HiveObjectPrivilege>();
        for (int i = 0; i < mUsers.size(); i++) {
          MGlobalPrivilege sUsr = mUsers.get(i);
          HiveObjectRef objectRef = new HiveObjectRef(
              HiveObjectType.GLOBAL, null, null, null, null);
          HiveObjectPrivilege secUser = new HiveObjectPrivilege(
              objectRef, sUsr.getPrincipalName(), principalType,
              new PrivilegeGrantInfo(sUsr.getPrivilege(), sUsr
                  .getCreateTime(), sUsr.getGrantor(), PrincipalType
                  .valueOf(sUsr.getGrantorType()), sUsr.getGrantOption()));
          result.add(secUser);
        }
        return result;
      } catch (MetaException e) {
        throw e;
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    }

    @Override
    public void cancel_delegation_token(String token_str_form)
        throws MetaException, TException {
      startFunction("cancel_delegation_token");
      boolean success = false;
      Exception ex = null;
      try {
        HiveMetaStore.cancelDelegationToken(token_str_form);
        success = true;
      } catch (IOException e) {
        ex = e;
        throw new MetaException(e.getMessage());
      } catch (Exception e) {
        ex = e;
        if (e instanceof MetaException) {
          throw (MetaException) e;
        } else if (e instanceof TException) {
          throw (TException) e;
        } else {
          throw newMetaException(e);
        }
      } finally {
        endFunction("cancel_delegation_token", success, ex);
      }
    }

    @Override
    public long renew_delegation_token(String token_str_form)
        throws MetaException, TException {
      startFunction("renew_delegation_token");
      Long ret = null;
      Exception ex = null;
      try {
        ret = HiveMetaStore.renewDelegationToken(token_str_form);
      } catch (IOException e) {
        ex = e;
        throw new MetaException(e.getMessage());
      } catch (Exception e) {
        ex = e;
        if (e instanceof MetaException) {
          throw (MetaException) e;
        } else if (e instanceof TException) {
          throw (TException) e;
        } else {
          throw newMetaException(e);
        }
      } finally {
        endFunction("renew_delegation_token", ret != null, ex);
      }
      return ret;
    }

    @Override
    public String get_delegation_token(String token_owner,
        String renewer_kerberos_principal_name)
        throws MetaException, TException {
      startFunction("get_delegation_token");
      String ret = null;
      Exception ex = null;
      try {
        ret =
            HiveMetaStore.getDelegationToken(token_owner,
                renewer_kerberos_principal_name);
      } catch (IOException e) {
        ex = e;
        throw new MetaException(e.getMessage());
      } catch (InterruptedException e) {
        ex = e;
        throw new MetaException(e.getMessage());
      } catch (Exception e) {
        ex = e;
        if (e instanceof MetaException) {
          throw (MetaException) e;
        } else if (e instanceof TException) {
          throw (TException) e;
        } else {
          throw newMetaException(e);
        }
      } finally {
        endFunction("get_delegation_token", ret != null, ex);
      }
      return ret;
    }

    @Override
    public void markPartitionForEvent(final String db_name, final String tbl_name,
        final Map<String, String> partName, final PartitionEventType evtType) throws
        MetaException, TException, NoSuchObjectException, UnknownDBException,
        UnknownTableException,
        InvalidPartitionException, UnknownPartitionException {

      Table tbl = null;
      Exception ex = null;
      try {
        startPartitionFunction("markPartitionForEvent", db_name, tbl_name, partName);
        firePreEvent(new PreLoadPartitionDoneEvent(db_name, tbl_name, partName, this));
        tbl = getMS().markPartitionForEvent(db_name, tbl_name, partName, evtType);
        if (null == tbl) {
          throw new UnknownTableException("Table: " + tbl_name + " not found.");
        } else {
          for (MetaStoreEventListener listener : listeners) {
            listener.onLoadPartitionDone(new LoadPartitionDoneEvent(true, tbl, partName, this));
          }
        }
      } catch (Exception original) {
        ex = original;
        LOG.error(original);
        if (original instanceof NoSuchObjectException) {
          throw (NoSuchObjectException) original;
        } else if (original instanceof UnknownTableException) {
          throw (UnknownTableException) original;
        } else if (original instanceof UnknownDBException) {
          throw (UnknownDBException) original;
        } else if (original instanceof UnknownPartitionException) {
          throw (UnknownPartitionException) original;
        } else if (original instanceof InvalidPartitionException) {
          throw (InvalidPartitionException) original;
        } else if (original instanceof MetaException) {
          throw (MetaException) original;
        } else {
          throw newMetaException(original);
        }
      } finally {
                endFunction("markPartitionForEvent", tbl != null, ex, tbl_name);
      }
    }

    @Override
    public boolean isPartitionMarkedForEvent(final String db_name, final String tbl_name,
        final Map<String, String> partName, final PartitionEventType evtType) throws
        MetaException, NoSuchObjectException, UnknownDBException, UnknownTableException,
        TException, UnknownPartitionException, InvalidPartitionException {

      startPartitionFunction("isPartitionMarkedForEvent", db_name, tbl_name, partName);
      Boolean ret = null;
      Exception ex = null;
      try {
        ret = getMS().isPartitionMarkedForEvent(db_name, tbl_name, partName, evtType);
      } catch (Exception original) {
        LOG.error(original);
        ex = original;
        if (original instanceof NoSuchObjectException) {
          throw (NoSuchObjectException) original;
        } else if (original instanceof UnknownTableException) {
          throw (UnknownTableException) original;
        } else if (original instanceof UnknownDBException) {
          throw (UnknownDBException) original;
        } else if (original instanceof UnknownPartitionException) {
          throw (UnknownPartitionException) original;
        } else if (original instanceof InvalidPartitionException) {
          throw (InvalidPartitionException) original;
        } else if (original instanceof MetaException) {
          throw (MetaException) original;
        } else {
          throw newMetaException(original);
        }
      } finally {
                endFunction("isPartitionMarkedForEvent", ret != null, ex, tbl_name);
      }

      return ret;
    }

    @Override
    public List<String> set_ugi(String username, List<String> groupNames) throws MetaException,
        TException {
      Collections.addAll(groupNames, username);
      return groupNames;
    }

    @Override
    public boolean partition_name_has_valid_characters(List<String> part_vals,
        boolean throw_exception) throws TException, MetaException {
      startFunction("partition_name_has_valid_characters");
      boolean ret = false;
      Exception ex = null;
      try {
        if (throw_exception) {
          MetaStoreUtils.validatePartitionNameCharacters(part_vals, partitionValidationPattern);
          ret = true;
        } else {
          ret = MetaStoreUtils.partitionNameHasValidCharacters(part_vals,
              partitionValidationPattern);
        }
      } catch (Exception e) {
        if (e instanceof MetaException) {
          throw (MetaException)e;
        } else {
          ex = e;
          throw newMetaException(e);
        }
      }
      endFunction("partition_name_has_valid_characters", true, null);
      return ret;
    }

    private static MetaException newMetaException(Exception e) {
      if (e instanceof MetaException) {
        return (MetaException)e;
      }
      MetaException me = new MetaException(e.toString());
      me.initCause(e);
      return me;
    }

    private void validateFunctionInfo(Function func) throws InvalidObjectException, MetaException {
      if (!MetaStoreUtils.validateName(func.getFunctionName())) {
        throw new InvalidObjectException(func.getFunctionName() + " is not a valid object name");
      }
      String className = func.getClassName();
      if (className == null) {
        throw new InvalidObjectException("Function class name cannot be null");
      }
    }

    @Override
    public void create_function(Function func) throws AlreadyExistsException,
        InvalidObjectException, MetaException, NoSuchObjectException,
        TException {
      validateFunctionInfo(func);

      boolean success = false;
      RawStore ms = getMS();
      try {
        ms.openTransaction();

        Database db = ms.getDatabase(func.getDbName());
        if (db == null) {
          throw new NoSuchObjectException("The database " + func.getDbName() + " does not exist");
        }
        Function existingFunc = ms.getFunction(func.getDbName(), func.getFunctionName());
        if (existingFunc != null) {
          throw new AlreadyExistsException(
              "Function " + func.getFunctionName() + " already exists");
        }

        // set create time
        long time = System.currentTimeMillis() / 1000;
        func.setCreateTime((int) time);
        ms.createFunction(func);
        success = ms.commitTransaction();
      } finally {
        if (!success) {
          ms.rollbackTransaction();
        }
      }
    }

    @Override
    public void drop_function(String dbName, String funcName)
        throws NoSuchObjectException, MetaException,
        InvalidObjectException, InvalidInputException {
      boolean success = false;
      Function func = null;
      RawStore ms = getMS();

      try {
        ms.openTransaction();

        func = ms.getFunction(dbName, funcName);
        if (func == null) {
          throw new NoSuchObjectException("Function " + funcName + " does not exist");
        }
        ms.dropFunction(dbName, funcName);
        success = ms.commitTransaction();
      } finally {
        if (!success) {
          ms.rollbackTransaction();
        }
      }
    }

    @Override
    public void alter_function(String dbName, String funcName, Function newFunc)
        throws InvalidOperationException, MetaException, TException {
      validateFunctionInfo(newFunc);
      boolean success = false;
      RawStore ms = getMS();
      try {
        ms.openTransaction();
        ms.alterFunction(dbName, funcName, newFunc);
        success = ms.commitTransaction();
      } finally {
        if (!success) {
          ms.rollbackTransaction();
        }
      }
    }

    @Override
    public List<String> get_functions(String dbName, String pattern)
        throws MetaException {
      startFunction("get_functions", ": db=" + dbName + " pat=" + pattern);

      RawStore ms = getMS();
      Exception ex = null;
      List<String> funcNames = null;

      try {
        funcNames = ms.getFunctions(dbName, pattern);
      } catch (Exception e) {
        ex = e;
        throw newMetaException(e);
      } finally {
        endFunction("get_functions", funcNames != null, ex);
      }

      return funcNames;
    }

    @Override
    public GetAllFunctionsResponse get_all_functions()
            throws MetaException {
      GetAllFunctionsResponse response = new GetAllFunctionsResponse();
      startFunction("get_all_functions");
      RawStore ms = getMS();
      List<Function> allFunctions = null;
      Exception ex = null;
      try {
        allFunctions = ms.getAllFunctions();
      } catch (Exception e) {
        ex = e;
        throw newMetaException(e);
      } finally {
        endFunction("get_all_functions", allFunctions != null, ex);
      }
      response.setFunctions(allFunctions);
      return response;
    }

    @Override
    public Function get_function(String dbName, String funcName)
        throws MetaException, NoSuchObjectException, TException {
      startFunction("get_function", ": " + dbName + "." + funcName);

      RawStore ms = getMS();
      Function func = null;
      Exception ex = null;

      try {
        func = ms.getFunction(dbName, funcName);
        if (func == null) {
          throw new NoSuchObjectException(
              "Function " + dbName + "." + funcName + " does not exist");
        }
      } catch (Exception e) {
        ex = e;
        throw newMetaException(e);
      } finally {
        endFunction("get_database", func != null, ex);
      }

      return func;
    }

    // Transaction and locking methods
    @Override
    public GetOpenTxnsResponse get_open_txns() throws TException {
      return getTxnHandler().getOpenTxns();
    }

    // Transaction and locking methods
    @Override
    public GetOpenTxnsInfoResponse get_open_txns_info() throws TException {
      return getTxnHandler().getOpenTxnsInfo();
    }

    @Override
    public OpenTxnsResponse open_txns(OpenTxnRequest rqst) throws TException {
      return getTxnHandler().openTxns(rqst);
    }

    @Override
    public void abort_txn(AbortTxnRequest rqst) throws NoSuchTxnException, TException {
      getTxnHandler().abortTxn(rqst);
    }

    @Override
    public void commit_txn(CommitTxnRequest rqst)
        throws NoSuchTxnException, TxnAbortedException, TException {
      getTxnHandler().commitTxn(rqst);
    }

    @Override
    public LockResponse lock(LockRequest rqst)
        throws NoSuchTxnException, TxnAbortedException, TException {
      return getTxnHandler().lock(rqst);
    }

    @Override
    public LockResponse check_lock(CheckLockRequest rqst)
        throws NoSuchTxnException, TxnAbortedException, NoSuchLockException, TException {
      return getTxnHandler().checkLock(rqst);
    }

    @Override
    public void unlock(UnlockRequest rqst)
        throws NoSuchLockException, TxnOpenException, TException {
      getTxnHandler().unlock(rqst);
    }

    @Override
    public ShowLocksResponse show_locks(ShowLocksRequest rqst) throws TException {
      return getTxnHandler().showLocks(rqst);
    }

    @Override
    public void heartbeat(HeartbeatRequest ids)
        throws NoSuchLockException, NoSuchTxnException, TxnAbortedException, TException {
      getTxnHandler().heartbeat(ids);
    }

    @Override
    public HeartbeatTxnRangeResponse heartbeat_txn_range(HeartbeatTxnRangeRequest rqst)
      throws TException {
      return getTxnHandler().heartbeatTxnRange(rqst);
    }

    @Override
    public void compact(CompactionRequest rqst) throws TException {
      getTxnHandler().compact(rqst);
    }

    @Override
    public ShowCompactResponse show_compact(ShowCompactRequest rqst) throws TException {
      return getTxnHandler().showCompact(rqst);
    }

    @Override
    public GetPrincipalsInRoleResponse get_principals_in_role(GetPrincipalsInRoleRequest request)
        throws MetaException, TException {

      incrementCounter("get_principals_in_role");
      firePreEvent(new PreAuthorizationCallEvent(this));
      Exception ex = null;
      List<MRoleMap> roleMaps = null;
      try {
        roleMaps = getMS().listRoleMembers(request.getRoleName());
      } catch (MetaException e) {
        throw e;
      } catch (Exception e) {
        ex = e;
        rethrowException(e);
      } finally {
        endFunction("get_principals_in_role", ex == null, ex);
      }
      return new GetPrincipalsInRoleResponse(getRolePrincipalGrants(roleMaps));
    }

    @Override
    public GetRoleGrantsForPrincipalResponse get_role_grants_for_principal(
        GetRoleGrantsForPrincipalRequest request) throws MetaException, TException {

      incrementCounter("get_role_grants_for_principal");
      firePreEvent(new PreAuthorizationCallEvent(this));
      Exception ex = null;
      List<MRoleMap> roleMaps = null;
      try {
        roleMaps = getMS().listRoles(request.getPrincipal_name(), request.getPrincipal_type());
      } catch (MetaException e) {
        throw e;
      } catch (Exception e) {
        ex = e;
        rethrowException(e);
      } finally {
        endFunction("get_role_grants_for_principal", ex == null, ex);
      }

      List<RolePrincipalGrant> roleGrantsList = getRolePrincipalGrants(roleMaps);
      return new GetRoleGrantsForPrincipalResponse(roleGrantsList);
    }

    /**
     * Convert each MRoleMap object into a thrift RolePrincipalGrant object
     * @param roleMaps
     * @return
     */
    private List<RolePrincipalGrant> getRolePrincipalGrants(List<MRoleMap> roleMaps) {
      List<RolePrincipalGrant> rolePrinGrantList = new ArrayList<RolePrincipalGrant>();
      if (roleMaps != null) {
        for (MRoleMap roleMap : roleMaps) {
          RolePrincipalGrant rolePrinGrant = new RolePrincipalGrant(
              roleMap.getRole().getRoleName(),
              roleMap.getPrincipalName(),
              PrincipalType.valueOf(roleMap.getPrincipalType()),
              roleMap.getGrantOption(),
              roleMap.getAddTime(),
              roleMap.getGrantor(),
              // no grantor type for public role, hence the null check
              roleMap.getGrantorType() == null ? null
                  : PrincipalType.valueOf(roleMap.getGrantorType())
              );
          rolePrinGrantList.add(rolePrinGrant);
        }
      }
      return rolePrinGrantList;
    }

    @Override
    public AggrStats get_aggr_stats_for(PartitionsStatsRequest request)
        throws NoSuchObjectException, MetaException, TException {
      String dbName = request.getDbName().toLowerCase();
      String tblName = request.getTblName().toLowerCase();
      startFunction("get_aggr_stats_for: db=" + request.getDbName() + " table=" + request.getTblName());

      List<String> lowerCaseColNames = new ArrayList<String>(request.getColNames().size());
      for (String colName : request.getColNames()) {
        lowerCaseColNames.add(colName.toLowerCase());
      }
      List<String> lowerCasePartNames = new ArrayList<String>(request.getPartNames().size());
      for (String partName : request.getPartNames()) {
        lowerCasePartNames.add(lowerCaseConvertPartName(partName));
      }
      AggrStats aggrStats = null;

      try {
        aggrStats = new AggrStats(getMS().get_aggr_stats_for(dbName, tblName, lowerCasePartNames,
            lowerCaseColNames));
        return aggrStats;
      } finally {
          endFunction("get_partitions_statistics_req: ", aggrStats == null, null, request.getTblName());
      }

    }

    @Override
    public boolean set_aggr_stats_for(SetPartitionsStatsRequest request)
        throws NoSuchObjectException, InvalidObjectException, MetaException, InvalidInputException,
        TException {
      boolean ret = true;
      for (ColumnStatistics colStats : request.getColStats()) {
        ret = ret && update_partition_column_statistics(colStats);
      }
      return ret;
    }

    @Override
    public NotificationEventResponse get_next_notification(NotificationEventRequest rqst)
        throws TException {
      RawStore ms = getMS();
      return ms.getNextNotification(rqst);
    }

    @Override
    public CurrentNotificationEventId get_current_notificationEventId() throws TException {
      RawStore ms = getMS();
      return ms.getCurrentNotificationEventId();
    }

    @VisibleForTesting
    public void updateMetrics() throws MetaException {
      initTableCount = getMS().getTableCount();
      initPartCount = getMS().getPartitionCount();
      initDatabaseCount = getMS().getDatabaseCount();
    }

    @Override
    public FireEventResponse fire_listener_event(FireEventRequest rqst) throws TException {
      switch (rqst.getData().getSetField()) {
        case INSERT_DATA:
          InsertEvent event = new InsertEvent(rqst.getDbName(), rqst.getTableName(),
              rqst.getPartitionVals(), rqst.getData().getInsertData().getFilesAdded(),
              rqst.isSuccessful(), this);
          for (MetaStoreEventListener listener : listeners) {
            listener.onInsert(event);
          }
          return new FireEventResponse();

        default:
          throw new TException("Event type " + rqst.getData().getSetField().toString() +
              " not currently supported.");
      }
    }
  }

  public static IHMSHandler newRetryingHMSHandler(IHMSHandler baseHandler, HiveConf hiveConf)
      throws MetaException {
    return newRetryingHMSHandler(baseHandler, hiveConf, false);
  }

  public static IHMSHandler newRetryingHMSHandler(IHMSHandler baseHandler, HiveConf hiveConf,
      boolean local) throws MetaException {
    return RetryingHMSHandler.getProxy(hiveConf, baseHandler, local);
  }

  public static Iface newRetryingHMSHandler(String name, HiveConf conf, boolean local)
      throws MetaException {
    HMSHandler baseHandler = new HiveMetaStore.HMSHandler(name, conf, false);
    return RetryingHMSHandler.getProxy(conf, baseHandler, local);
  }

  /**
   * Discard a current delegation token.
   *
   * @param tokenStrForm
   *          the token in string form
   */
  public static void cancelDelegationToken(String tokenStrForm
      ) throws IOException {
    saslServer.cancelDelegationToken(tokenStrForm);
  }

  /**
   * Get a new delegation token.
   *
   * @param renewer
   *          the designated renewer
   */
  public static String getDelegationToken(String owner, String renewer)
      throws IOException, InterruptedException {
    return saslServer.getDelegationToken(owner, renewer);
  }

  /**
   * @return true if remote metastore has been created
   */
  public static boolean isMetaStoreRemote() {
    return isMetaStoreRemote;
  }

  /**updateMetric
   * Renew a delegation token to extend its lifetime.
   *
   * @param tokenStrForm
   *          the token in string form
   */
  public static long renewDelegationToken(String tokenStrForm
      ) throws IOException {
    return saslServer.renewDelegationToken(tokenStrForm);
  }

  /**
   * HiveMetaStore specific CLI
   *
   */
  static public class HiveMetastoreCli extends CommonCliOptions {
    int port = DEFAULT_HIVE_METASTORE_PORT;

    @SuppressWarnings("static-access")
    public HiveMetastoreCli() {
      super("hivemetastore", true);

      // -p port
      OPTIONS.addOption(OptionBuilder
          .hasArg()
          .withArgName("port")
          .withDescription("Hive Metastore port number, default:"
              + DEFAULT_HIVE_METASTORE_PORT)
          .create('p'));

    }

    @Override
    public void parse(String[] args) {
      super.parse(args);

      // support the old syntax "hivemetastore [port]" but complain
      args = commandLine.getArgs();
      if (args.length > 0) {
        // complain about the deprecated syntax -- but still run
        System.err.println(
            "This usage has been deprecated, consider using the new command "
                + "line syntax (run with -h to see usage information)");

        port = new Integer(args[0]);
      }

      // notice that command line options take precedence over the
      // deprecated (old style) naked args...
      if (commandLine.hasOption('p')) {
        port = Integer.parseInt(commandLine.getOptionValue('p'));
      } else {
        // legacy handling
        String metastorePort = System.getenv("METASTORE_PORT");
        if (metastorePort != null) {
          port = Integer.parseInt(metastorePort);
        }
      }
    }
  }

  /**
   * @param args
   */
  public static void main(String[] args) throws Throwable {
    HiveConf.setLoadMetastoreConfig(true);
    HiveMetastoreCli cli = new HiveMetastoreCli();
    cli.parse(args);
    final boolean isCliVerbose = cli.isVerbose();
    // NOTE: It is critical to do this prior to initializing log4j, otherwise
    // any log specific settings via hiveconf will be ignored
    Properties hiveconf = cli.addHiveconfToSystemProperties();

    // If the log4j.configuration property hasn't already been explicitly set,
    // use Hive's default log4j configuration
    if (System.getProperty("log4j.configuration") == null) {
      // NOTE: It is critical to do this here so that log4j is reinitialized
      // before any of the other core hive classes are loaded
      try {
        LogUtils.initHiveLog4j();
      } catch (LogInitializationException e) {
        HMSHandler.LOG.warn(e.getMessage());
      }
    }

    try {
      String msg = "Starting hive metastore on port " + cli.port;
      HMSHandler.LOG.info(msg);
      if (cli.isVerbose()) {
        System.err.println(msg);
      }

      final HiveConf conf = new HiveConf(HMSHandler.class);

      // set all properties specified on the command line
      for (Map.Entry<Object, Object> item : hiveconf.entrySet()) {
        conf.set((String) item.getKey(), (String) item.getValue());
      }

      // Add shutdown hook.
      Runtime.getRuntime().addShutdownHook(new Thread() {
        @Override
        public void run() {
          String shutdownMsg = "Shutting down hive metastore.";
          HMSHandler.LOG.info(shutdownMsg);
          if (isCliVerbose) {
            System.err.println(shutdownMsg);
          }
          if (conf.getBoolVar(ConfVars.METASTORE_METRICS)) {
            try {
              MetricsFactory.close();
            } catch (Exception e) {
              LOG.error("error in Metrics deinit: " + e.getClass().getName() + " "
                + e.getMessage(), e);
            }
          }
        }
      });

      //Start Metrics for Standalone (Remote) Mode
      if (conf.getBoolVar(ConfVars.METASTORE_METRICS)) {
        try {
          MetricsFactory.init(conf);
        } catch (Exception e) {
          // log exception, but ignore inability to start
          LOG.error("error in Metrics init: " + e.getClass().getName() + " "
            + e.getMessage(), e);
        }
      }

      Lock startLock = new ReentrantLock();
      Condition startCondition = startLock.newCondition();
      AtomicBoolean startedServing = new AtomicBoolean();
      startMetaStoreThreads(conf, startLock, startCondition, startedServing);
      startMetaStore(cli.port, ShimLoader.getHadoopThriftAuthBridge(), conf, startLock,
          startCondition, startedServing);
    } catch (Throwable t) {
      // Catch the exception, log it and rethrow it.
      HMSHandler.LOG
          .error("Metastore Thrift Server threw an exception...", t);
      throw t;
    }
  }

  /**
   * Start Metastore based on a passed {@link HadoopThriftAuthBridge}
   *
   * @param port
   * @param bridge
   * @throws Throwable
   */
  public static void startMetaStore(int port, HadoopThriftAuthBridge bridge)
      throws Throwable {
    startMetaStore(port, bridge, new HiveConf(HMSHandler.class), null, null, null);
  }

  /**
   * Start the metastore store.
   * @param port
   * @param bridge
   * @param conf
   * @throws Throwable
   */
  public static void startMetaStore(int port, HadoopThriftAuthBridge bridge,
                                    HiveConf conf) throws Throwable {
    startMetaStore(port, bridge, conf, null, null, null);
  }

  /**
   * Start Metastore based on a passed {@link HadoopThriftAuthBridge}
   *
   * @param port
   * @param bridge
   * @param conf
   *          configuration overrides
   * @throws Throwable
   */
  public static void startMetaStore(int port, HadoopThriftAuthBridge bridge,
      HiveConf conf, Lock startLock, Condition startCondition,
      AtomicBoolean startedServing) throws Throwable {
    try {
      isMetaStoreRemote = true;
      // Server will create new threads up to max as necessary. After an idle
      // period, it will destroy threads to keep the number of threads in the
      // pool to min.
      int maxMessageSize = conf.getIntVar(HiveConf.ConfVars.METASTORESERVERMAXMESSAGESIZE);
      int minWorkerThreads = conf.getIntVar(HiveConf.ConfVars.METASTORESERVERMINTHREADS);
      int maxWorkerThreads = conf.getIntVar(HiveConf.ConfVars.METASTORESERVERMAXTHREADS);
      boolean tcpKeepAlive = conf.getBoolVar(HiveConf.ConfVars.METASTORE_TCP_KEEP_ALIVE);
      boolean useFramedTransport = conf.getBoolVar(ConfVars.METASTORE_USE_THRIFT_FRAMED_TRANSPORT);
      boolean useCompactProtocol = conf.getBoolVar(ConfVars.METASTORE_USE_THRIFT_COMPACT_PROTOCOL);
      useSasl = conf.getBoolVar(HiveConf.ConfVars.METASTORE_USE_THRIFT_SASL);

      TServerTransport serverTransport = tcpKeepAlive ?
          new TServerSocketKeepAlive(port) : new TServerSocket(port);

      TProcessor processor;
      TTransportFactory transFactory;
      final TProtocolFactory protocolFactory;
      final TProtocolFactory inputProtoFactory;
      if (useCompactProtocol) {
        protocolFactory = new TCompactProtocol.Factory();
        inputProtoFactory = new TCompactProtocol.Factory(maxMessageSize, maxMessageSize);
      } else {
        protocolFactory = new TBinaryProtocol.Factory();
        inputProtoFactory = new TBinaryProtocol.Factory(true, true, maxMessageSize, maxMessageSize);
      }
      HMSHandler baseHandler = new HiveMetaStore.HMSHandler("new db based metaserver", conf,
          false);
      IHMSHandler handler = newRetryingHMSHandler(baseHandler, conf);
      if (useSasl) {
        // we are in secure mode.
        if (useFramedTransport) {
          throw new HiveMetaException("Framed transport is not supported with SASL enabled.");
        }
        saslServer = bridge.createServer(
            conf.getVar(HiveConf.ConfVars.METASTORE_KERBEROS_KEYTAB_FILE),
            conf.getVar(HiveConf.ConfVars.METASTORE_KERBEROS_PRINCIPAL));
        // start delegation token manager
        saslServer.startDelegationTokenSecretManager(conf, baseHandler, ServerMode.METASTORE);
        transFactory = saslServer.createTransportFactory(
                MetaStoreUtils.getMetaStoreSaslProperties(conf));
        processor = saslServer.wrapProcessor(
          new ThriftHiveMetastore.Processor<IHMSHandler>(handler));
        LOG.info("Starting DB backed MetaStore Server in Secure Mode");
      } else {
        // we are in unsecure mode.
        if (conf.getBoolVar(ConfVars.METASTORE_EXECUTE_SET_UGI)) {
          transFactory = useFramedTransport ?
              new ChainedTTransportFactory(new TFramedTransport.Factory(),
                  new TUGIContainingTransport.Factory())
              : new TUGIContainingTransport.Factory();

          processor = new TUGIBasedProcessor<IHMSHandler>(handler);
          LOG.info("Starting DB backed MetaStore Server with SetUGI enabled");
        } else {
          transFactory = useFramedTransport ?
              new TFramedTransport.Factory() : new TTransportFactory();
          processor = new TSetIpAddressProcessor<IHMSHandler>(handler);
          LOG.info("Starting DB backed MetaStore Server");
        }
      }

      TThreadPoolServer.Args args = new TThreadPoolServer.Args(serverTransport)
          .processor(processor)
          .transportFactory(transFactory)
          .protocolFactory(protocolFactory)
          .inputProtocolFactory(inputProtoFactory)
          .minWorkerThreads(minWorkerThreads)
          .maxWorkerThreads(maxWorkerThreads);

      TServer tServer = new TThreadPoolServer(args);
      TServerEventHandler tServerEventHandler = new TServerEventHandler() {
        @Override
        public void preServe() {
        }

        @Override
        public ServerContext createContext(TProtocol tProtocol, TProtocol tProtocol1) {
          try {
            Metrics metrics = MetricsFactory.getInstance();
            if (metrics != null) {
              metrics.incrementCounter(MetricsConstant.OPEN_CONNECTIONS);
            }
          } catch (Exception e) {
            LOG.warn("Error Reporting Metastore open connection to Metrics system", e);
          }
          return null;
        }

        @Override
        public void deleteContext(ServerContext serverContext, TProtocol tProtocol, TProtocol tProtocol1) {
          try {
            Metrics metrics = MetricsFactory.getInstance();
            if (metrics != null) {
              metrics.decrementCounter(MetricsConstant.OPEN_CONNECTIONS);
            }
          } catch (Exception e) {
            LOG.warn("Error Reporting Metastore close connection to Metrics system", e);
          }
          // If the IMetaStoreClient#close was called, HMSHandler#shutdown would have already
          // cleaned up thread local RawStore. Otherwise, do it now.
          cleanupRawStore();
        }

        @Override
        public void processContext(ServerContext serverContext, TTransport tTransport, TTransport tTransport1) {
        }
      };

      tServer.setServerEventHandler(tServerEventHandler);
      HMSHandler.LOG.info("Started the new metaserver on port [" + port
          + "]...");
      HMSHandler.LOG.info("Options.minWorkerThreads = "
          + minWorkerThreads);
      HMSHandler.LOG.info("Options.maxWorkerThreads = "
          + maxWorkerThreads);
      HMSHandler.LOG.info("TCP keepalive = " + tcpKeepAlive);

      if (startLock != null) {
        signalOtherThreadsToStart(tServer, startLock, startCondition, startedServing);
      }
      tServer.serve();
    } catch (Throwable x) {
      x.printStackTrace();
      HMSHandler.LOG.error(StringUtils.stringifyException(x));
      throw x;
    }
  }

  private static void cleanupRawStore() {
    RawStore rs = HMSHandler.getRawStore();
    if (rs != null) {
      HMSHandler.logInfo("Cleaning up thread local RawStore...");
      try {
        rs.shutdown();
      } finally {
        HMSHandler.threadLocalConf.remove();
        HMSHandler.removeRawStore();
      }
      HMSHandler.logInfo("Done cleaning up thread local RawStore");
    }
  }

  private static void signalOtherThreadsToStart(final TServer server, final Lock startLock,
                                                final Condition startCondition,
                                                final AtomicBoolean startedServing) {
    // A simple thread to wait until the server has started and then signal the other threads to
    // begin
    Thread t = new Thread() {
      @Override
      public void run() {
        do {
          try {
            Thread.sleep(1000);
          } catch (InterruptedException e) {
            LOG.warn("Signalling thread was interuppted: " + e.getMessage());
          }
        } while (!server.isServing());
        startLock.lock();
        try {
          startedServing.set(true);
          startCondition.signalAll();
        } finally {
          startLock.unlock();
        }
      }
    };
    t.start();
  }

  /**
   * Start threads outside of the thrift service, such as the compactor threads.
   * @param conf Hive configuration object
   */
  private static void startMetaStoreThreads(final HiveConf conf, final Lock startLock,
                                            final Condition startCondition, final
                                            AtomicBoolean startedServing) {
    // A thread is spun up to start these other threads.  That's because we can't start them
    // until after the TServer has started, but once TServer.serve is called we aren't given back
    // control.
    Thread t = new Thread() {
      @Override
      public void run() {
        // This is a massive hack.  The compactor threads have to access packages in ql (such as
        // AcidInputFormat).  ql depends on metastore so we can't directly access those.  To deal
        // with this the compactor thread classes have been put in ql and they are instantiated here
        // dyanmically.  This is not ideal but it avoids a massive refactoring of Hive packages.
        //
        // Wrap the start of the threads in a catch Throwable loop so that any failures
        // don't doom the rest of the metastore.
        startLock.lock();
        try {
          JvmPauseMonitor pauseMonitor = new JvmPauseMonitor(conf);
          pauseMonitor.start();
        } catch (Throwable t) {
          LOG.warn("Could not initiate the JvmPauseMonitor thread." + " GCs and Pauses may not be " +
            "warned upon.", t);
        }

        try {
          // Per the javadocs on Condition, do not depend on the condition alone as a start gate
          // since spurious wake ups are possible.
          while (!startedServing.get()) startCondition.await();
          startCompactorInitiator(conf);
          startCompactorWorkers(conf);
          startCompactorCleaner(conf);
        } catch (Throwable e) {
          LOG.error("Failure when starting the compactor, compactions may not happen, " +
              StringUtils.stringifyException(e));
        } finally {
          startLock.unlock();
        }
      }
    };

    t.start();
  }

  private static void startCompactorInitiator(HiveConf conf) throws Exception {
    if (HiveConf.getBoolVar(conf, HiveConf.ConfVars.HIVE_COMPACTOR_INITIATOR_ON)) {
      MetaStoreThread initiator =
          instantiateThread("org.apache.hadoop.hive.ql.txn.compactor.Initiator");
      initializeAndStartThread(initiator, conf);
    }
  }

  private static void startCompactorWorkers(HiveConf conf) throws Exception {
    int numWorkers = HiveConf.getIntVar(conf, HiveConf.ConfVars.HIVE_COMPACTOR_WORKER_THREADS);
    for (int i = 0; i < numWorkers; i++) {
      MetaStoreThread worker =
          instantiateThread("org.apache.hadoop.hive.ql.txn.compactor.Worker");
      initializeAndStartThread(worker, conf);
    }
  }

  private static void startCompactorCleaner(HiveConf conf) throws Exception {
    if (HiveConf.getBoolVar(conf, HiveConf.ConfVars.HIVE_COMPACTOR_INITIATOR_ON)) {
      MetaStoreThread cleaner =
          instantiateThread("org.apache.hadoop.hive.ql.txn.compactor.Cleaner");
      initializeAndStartThread(cleaner, conf);
    }
  }

  private static MetaStoreThread instantiateThread(String classname) throws Exception {
    Class c = Class.forName(classname);
    Object o = c.newInstance();
    if (MetaStoreThread.class.isAssignableFrom(o.getClass())) {
      return (MetaStoreThread)o;
    } else {
      String s = classname + " is not an instance of MetaStoreThread.";
      LOG.error(s);
      throw new IOException(s);
    }
  }

  private static int nextThreadId = 1000000;

  private static void initializeAndStartThread(MetaStoreThread thread, HiveConf conf) throws
      MetaException {
    LOG.info("Starting metastore thread of type " + thread.getClass().getName());
    thread.setHiveConf(conf);
    thread.setThreadId(nextThreadId++);
    thread.init(new AtomicBoolean(), new AtomicBoolean());
    thread.start();
  }
}