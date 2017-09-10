package waldap.core.ldap;/*
 *  Licensed to the Apache Software Foundation (ASF) under one
 *  or more contributor license agreements.  See the NOTICE file
 *  distributed with this work for additional information
 *  regarding copyright ownership.  The ASF licenses this file
 *  to you under the Apache License, Version 2.0 (the
 *  "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 *
 */

import java.io.File;
import java.util.List;

import org.apache.directory.api.ldap.model.constants.SchemaConstants;
import org.apache.directory.api.ldap.model.entry.DefaultEntry;
import org.apache.directory.api.ldap.model.ldif.LdifEntry;
import org.apache.directory.api.ldap.model.ldif.LdifReader;
import org.apache.directory.api.ldap.model.schema.LdapComparator;
import org.apache.directory.api.ldap.model.schema.SchemaManager;
import org.apache.directory.api.ldap.model.schema.comparators.NormalizingComparator;
import org.apache.directory.api.ldap.model.schema.registries.ComparatorRegistry;
import org.apache.directory.api.ldap.model.schema.registries.SchemaLoader;
import org.apache.directory.api.ldap.schema.loader.JarLdifSchemaLoader;
import org.apache.directory.api.ldap.schema.manager.impl.DefaultSchemaManager;
import org.apache.directory.api.util.exception.Exceptions;
import org.apache.directory.server.constants.ServerDNConstants;
import org.apache.directory.server.core.DefaultDirectoryService;
import org.apache.directory.server.core.api.CacheService;
import org.apache.directory.server.core.api.CoreSession;
import org.apache.directory.server.core.api.DirectoryService;
import org.apache.directory.server.core.api.InstanceLayout;
import org.apache.directory.server.core.api.partition.Partition;
import org.apache.directory.server.core.api.schema.SchemaPartition;
import org.apache.directory.server.core.factory.*;
import org.apache.directory.server.i18n.I18n;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.sf.ehcache.CacheManager;
import net.sf.ehcache.config.CacheConfiguration;
import net.sf.ehcache.config.Configuration;

import waldap.core.util.Directory;

/**
 * Factory for a fast (mostly in-memory-only) ApacheDS DirectoryService. Use only for tests!!
 *
 * @author Josef Cacek
 */
public class LdapandaDirectoryServiceFactory implements DirectoryServiceFactory {

    private static Logger LOG = LoggerFactory.getLogger(LdapandaDirectoryServiceFactory.class);

    private final DirectoryService directoryService;
    private final PartitionFactory partitionFactory;

    /**
     * Default constructor which creates {@link DefaultDirectoryService} instance and configures {@link AvlPartitionFactory} as
     * the {@link PartitionFactory} implementation.
     */
    public LdapandaDirectoryServiceFactory() {
        try {
            directoryService = new DefaultDirectoryService();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        directoryService.setShutdownHookEnabled(false);
        partitionFactory = new LdifPartitionFactory();
    }

    /**
     * Constructor which uses provided {@link DirectoryService} and {@link PartitionFactory} implementations.
     *
     * @param directoryService must be not-<code>null</code>
     * @param partitionFactory must be not-<code>null</code>
     */
    public LdapandaDirectoryServiceFactory(DirectoryService directoryService, PartitionFactory partitionFactory) {
        this.directoryService = directoryService;
        this.partitionFactory = partitionFactory;
    }

    /**
     * {@inheritDoc}
     */
    public void init(String name) throws Exception {
        if ((directoryService != null) && directoryService.isStarted()) {
            return;
        }

        directoryService.setInstanceId(name);

        // instance layout
        InstanceLayout instanceLayout = new InstanceLayout(Directory.IncetanceHome());

        directoryService.setInstanceLayout(instanceLayout);

        // EhCache in disabled-like-mode
        Configuration ehCacheConfig = new Configuration();
        CacheConfiguration defaultCache = new CacheConfiguration("default", 1)
                .eternal(false).timeToIdleSeconds(30)
                .timeToLiveSeconds(30);
        ehCacheConfig.addDefaultCache(defaultCache);
        CacheService cacheService = new CacheService(new CacheManager(ehCacheConfig));
        directoryService.setCacheService(cacheService);

        // Init the schema
        // SchemaLoader loader = new SingleLdifSchemaLoader();
        SchemaLoader loader = new JarLdifSchemaLoader();
        SchemaManager schemaManager = new DefaultSchemaManager(loader);
        schemaManager.loadAllEnabled();
        ComparatorRegistry comparatorRegistry = schemaManager.getComparatorRegistry();
        for (LdapComparator<?> comparator : comparatorRegistry) {
            if (comparator instanceof NormalizingComparator) {
                ((NormalizingComparator) comparator).setOnServer();
            }
        }
        directoryService.setSchemaManager(schemaManager);
        InMemorySchemaPartition inMemorySchemaPartition = new InMemorySchemaPartition(schemaManager);

        SchemaPartition schemaPartition = new SchemaPartition(schemaManager);
        schemaPartition.setWrappedPartition(inMemorySchemaPartition);
        directoryService.setSchemaPartition(schemaPartition);
        List<Throwable> errors = schemaManager.getErrors();
        if (errors.size() != 0) {
            throw new Exception(I18n.err(I18n.ERR_317, Exceptions.printErrors(errors)));
        }

        // Init system partition
        Partition systemPartition = partitionFactory.createPartition(directoryService.getSchemaManager(),
                directoryService.getDnFactory(), "system", ServerDNConstants.SYSTEM_DN, 500,
                new File(directoryService.getInstanceLayout().getPartitionsDirectory(), "system"));
        systemPartition.setSchemaManager(directoryService.getSchemaManager());
        partitionFactory.addIndex(systemPartition, SchemaConstants.OBJECT_CLASS_AT, 100);
        directoryService.setSystemPartition(systemPartition);

        Partition dataPartition = partitionFactory.createPartition(directoryService.getSchemaManager(),
                directoryService.getDnFactory(), "waldap", "o=waldap", 500,
                new File(directoryService.getInstanceLayout().getPartitionsDirectory(), "waldap"));
        directoryService.addPartition(dataPartition);

        directoryService.setAccessControlEnabled(true);
        directoryService.setAllowAnonymousAccess(true);
        //directoryService.setPasswordHidden(true);

        directoryService.startup();

        CoreSession session = directoryService.getAdminSession();
        if (!session.exists("o=waldap")){
            LdifReader reader = new LdifReader(getClass().getResourceAsStream("/base.ldif"));
            for (LdifEntry entry: reader) {
                session.add(new DefaultEntry(directoryService.getSchemaManager(), entry.getEntry()));
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    public DirectoryService getDirectoryService() throws Exception {
        return directoryService;
    }

    /**
     * {@inheritDoc}
     */
    public PartitionFactory getPartitionFactory() throws Exception {
        return partitionFactory;
    }

}
