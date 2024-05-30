/**
 * Copyright © 2016-2024 The Thingsboard Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.thingsboard.server.service.sync.tenant;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.thingsboard.common.util.ThingsBoardThreadFactory;
import org.thingsboard.server.common.data.AttributeScope;
import org.thingsboard.server.common.data.ObjectType;
import org.thingsboard.server.common.data.Tenant;
import org.thingsboard.server.common.data.audit.AuditLog;
import org.thingsboard.server.common.data.event.Event;
import org.thingsboard.server.common.data.event.EventType;
import org.thingsboard.server.common.data.id.EntityId;
import org.thingsboard.server.common.data.id.HasId;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.kv.AttributeKv;
import org.thingsboard.server.common.data.kv.AttributeKvEntry;
import org.thingsboard.server.common.data.kv.LatestTsKv;
import org.thingsboard.server.common.data.kv.TsKvEntry;
import org.thingsboard.server.common.data.page.PageDataIterable;
import org.thingsboard.server.common.data.page.TimePageLink;
import org.thingsboard.server.common.data.relation.EntityRelation;
import org.thingsboard.server.dao.TenantEntityDao;
import org.thingsboard.server.dao.attributes.AttributesDao;
import org.thingsboard.server.dao.audit.AuditLogDao;
import org.thingsboard.server.dao.entity.EntityDaoRegistry;
import org.thingsboard.server.dao.event.EventDao;
import org.thingsboard.server.dao.model.ModelConstants;
import org.thingsboard.server.dao.relation.RelationDao;
import org.thingsboard.server.dao.sqlts.insert.sql.SqlPartitioningRepository;
import org.thingsboard.server.dao.tenant.TenantDao;
import org.thingsboard.server.dao.timeseries.TimeseriesLatestDao;
import org.thingsboard.server.service.sync.tenant.util.DataWrapper;
import org.thingsboard.server.service.sync.tenant.util.Storage;
import org.thingsboard.server.service.sync.tenant.util.TenantExportConfig;
import org.thingsboard.server.service.sync.tenant.util.TenantExportResult;

import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.thingsboard.server.common.data.ObjectType.ATTRIBUTE_KV;
import static org.thingsboard.server.common.data.ObjectType.AUDIT_LOG;
import static org.thingsboard.server.common.data.ObjectType.EVENT;
import static org.thingsboard.server.common.data.ObjectType.LATEST_TS_KV;
import static org.thingsboard.server.common.data.ObjectType.RELATION;
import static org.thingsboard.server.common.data.ObjectType.TENANT;
import static org.thingsboard.server.common.data.ObjectType.values;

@Service
@RequiredArgsConstructor
@Slf4j
public class TenantExportService {

    private final Storage storage;
    private final EntityDaoRegistry entityDaoRegistry;

    private final TenantDao tenantDao;
    private final EventDao eventDao;
    private final AuditLogDao auditLogDao;
    private final AttributesDao attributesDao;
    private final RelationDao relationDao;
    private final TimeseriesLatestDao timeseriesLatestDao;
    private final SqlPartitioningRepository partitioningRepository;

    private final ExecutorService executor = Executors.newSingleThreadExecutor(ThingsBoardThreadFactory.forName("tenant-export"));
    private Cache<UUID, TenantExportResult> results;

    @PostConstruct
    private void init() {
        results = Caffeine.newBuilder()
                .expireAfterAccess(24, TimeUnit.HOURS)
                .<UUID, TenantExportResult>removalListener((tenantId, result, removalCause) -> {
                    if (tenantId != null) {
                        storage.cleanUpExportData(tenantId);
                    }
                })
                .build();
    }

    public UUID exportTenant(TenantExportConfig config) {
        TenantId tenantId = TenantId.fromUUID(config.getTenantId());
        log.info("[{}] Exporting tenant", tenantId);
        Tenant tenant = tenantDao.findById(TenantId.SYS_TENANT_ID, tenantId.getId());
        if (tenant == null) {
            throw new IllegalArgumentException("Tenant with id " + tenantId + " not found");
        }
        TenantExportResult result = new TenantExportResult();
        executor.submit(() -> {
            try {
                exportTenant(tenant, config);
                result.setSuccess(true);
                result.setDone(true);
            } catch (Exception e) {
                log.error("Failed to export tenant {}", tenant, e);
                result.setError(ExceptionUtils.getStackTrace(e));
                result.setDone(true);
            }
        });

        results.put(tenant.getUuidId(), result);
        return tenant.getUuidId();
    }

    private void exportTenant(Tenant tenant, TenantExportConfig config) {
        TenantId tenantId = tenant.getId();
        storage.init(tenantId.getId());
        Set<ObjectType> skipped = EnumSet.of(TENANT, RELATION, EVENT, ATTRIBUTE_KV, LATEST_TS_KV, AUDIT_LOG);
        if (config.getSkipped() != null) {
            skipped.addAll(config.getSkipped());
        }

        save(tenantId, TENANT, tenant);
        for (ObjectType type : values()) {
            if (skipped.contains(type)) {
                continue;
            }

            log.debug("[{}] Exporting {} entities", tenantId, type);
            TenantEntityDao<?> dao = entityDaoRegistry.getTenantEntityDao(type);
            var entities = new PageDataIterable<>(pageLink -> dao.findAllByTenantId(tenantId, pageLink), 100);

            for (Object entity : entities) {
                save(tenantId, type, entity);

                if (entity instanceof HasId<?> hasId && hasId.getId() instanceof EntityId entityId) {
                    exportRelations(tenantId, entityId);
                    exportEvents(tenantId, entityId);
                    exportAttributes(tenantId, entityId);
                    exportLatestTelemetry(tenantId, entityId);
                }
            }

            AtomicInteger count = getResult(tenantId.getId()).getStats().get(type);
            log.debug("[{}] Exported {} {} entities", tenantId, count != null ? count : 0, type);
        }
        exportAuditLogs(tenantId);

        storage.archiveExportData(tenantId.getId());
    }

    private void exportAttributes(TenantId tenantId, EntityId entityId) {
        for (AttributeScope attributeScope : AttributeScope.values()) {
            List<AttributeKvEntry> attributes = attributesDao.findAll(tenantId, entityId, attributeScope);
            for (AttributeKvEntry entry : attributes) {
                AttributeKv attributeKv = new AttributeKv(entityId, attributeScope, entry);
                save(tenantId, ATTRIBUTE_KV, attributeKv);
            }
        }
    }

    private void exportRelations(TenantId tenantId, EntityId entityId) {
        List<EntityRelation> relations = relationDao.findAllByFrom(tenantId, entityId);
        for (EntityRelation relation : relations) {
            save(tenantId, RELATION, relation);
        }
    }

    @SneakyThrows
    private void exportLatestTelemetry(TenantId tenantId, EntityId entityId) {
        List<TsKvEntry> latestTelemetry = timeseriesLatestDao.findAllLatest(tenantId, entityId).get();
        for (TsKvEntry tsKvEntry : latestTelemetry) {
            LatestTsKv latestTsKv = new LatestTsKv(entityId, tsKvEntry);
            save(tenantId, LATEST_TS_KV, latestTsKv);
        }
    }

    private void exportAuditLogs(TenantId tenantId) {
        Map<Long, Long> partitions = getPartitions(ModelConstants.AUDIT_LOG_TABLE_NAME);
        partitions.forEach((startTime, endTime) -> {
            PageDataIterable<AuditLog> auditLogs = new PageDataIterable<>(pageLink -> {
                return auditLogDao.findAuditLogsByTenantId(tenantId.getId(), null, new TimePageLink(pageLink, startTime, endTime));
            }, 512);
            for (AuditLog auditLog : auditLogs) {
                save(tenantId, AUDIT_LOG, auditLog);
            }
        });
    }

    private void exportEvents(TenantId tenantId, EntityId entityId) {
        for (EventType eventType : EventType.values()) {
            Map<Long, Long> partitions = getPartitions(eventType.getTable());
            partitions.forEach((startTime, endTime) -> {
                PageDataIterable<? extends Event> events = new PageDataIterable<>(pageLink -> {
                    return eventDao.findEvents(tenantId.getId(), entityId.getId(), eventType, new TimePageLink(pageLink, startTime, endTime));
                }, 512);
                for (Event event : events) {
                    save(tenantId, EVENT, event);
                }
            });
        }
    }

    private void save(TenantId tenantId, ObjectType type, Object entity) {
        storage.save(tenantId.getId(), type, DataWrapper.of(entity));
        getResult(tenantId.getId()).report(type);
        log.trace("[{}][{}] Saved entity {}", tenantId, type, entity);
    }

    private Map<Long, Long> getPartitions(String table) {
        List<Long> partitionsStartTime = partitioningRepository.fetchPartitions(table).stream().sorted().toList();
        if (partitionsStartTime.isEmpty()) {
            return Collections.emptyMap();
        }

        Map<Long, Long> partitions = new HashMap<>();
        for (int i = 0; i < partitionsStartTime.size(); i++) {
            Long startTime = partitionsStartTime.get(i);
            Long endTime;
            if (partitionsStartTime.size() - 1 == i) {
                endTime = System.currentTimeMillis();
            } else {
                endTime = partitionsStartTime.get(i + 1) - 1;
            }
            partitions.put(startTime, endTime);
        }
        return partitions;
    }

    public TenantExportResult getResult(UUID tenantId) {
        TenantExportResult result = results.getIfPresent(tenantId);
        if (result == null) {
            throw new IllegalStateException("Export result for tenant id " + tenantId + " not found");
        }
        return result;
    }

    public ResponseEntity<InputStreamResource> downloadResult(UUID tenantId) {
        TenantExportResult result = getResult(tenantId);
        if (!result.isDone()) {
            throw new IllegalStateException("Not ready yet");
        } else if (!result.isSuccess()) {
            throw new IllegalStateException("Tenant export failed: " + result.getError());
        }

        String fileName = "data.tar";
        return ResponseEntity.ok()
                .header("Content-Type", "")
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment;filename=" + fileName)
                .header("x-filename", fileName)
                .body(new InputStreamResource(storage.downloadExportData(tenantId)));
    }

}
