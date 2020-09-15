/**
 * Copyright © 2016-2020 The Thingsboard Authors
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
package org.thingsboard.rule.engine.rpc;

import com.datastax.driver.core.utils.UUIDs;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;
import org.thingsboard.rule.engine.api.RuleEngineDeviceRpcRequest;
import org.thingsboard.rule.engine.api.RuleNode;
import org.thingsboard.rule.engine.api.TbContext;
import org.thingsboard.rule.engine.api.TbNode;
import org.thingsboard.rule.engine.api.TbNodeConfiguration;
import org.thingsboard.rule.engine.api.TbNodeException;
import org.thingsboard.rule.engine.api.TbRelationTypes;
import org.thingsboard.rule.engine.api.util.TbNodeUtils;
import org.thingsboard.server.common.data.DataConstants;
import org.thingsboard.server.common.data.EntityType;
import org.thingsboard.server.common.data.audit.ActionType;
import org.thingsboard.server.common.data.edge.EdgeEvent;
import org.thingsboard.server.common.data.edge.EdgeEventType;
import org.thingsboard.server.common.data.id.DeviceId;
import org.thingsboard.server.common.data.id.EdgeId;
import org.thingsboard.server.common.data.plugin.ComponentType;
import org.thingsboard.server.common.data.relation.EntityRelation;
import org.thingsboard.server.common.data.relation.RelationTypeGroup;
import org.thingsboard.server.common.msg.TbMsg;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Slf4j
@RuleNode(
        type = ComponentType.ACTION,
        name = "rpc call request",
        configClazz = TbSendRpcRequestNodeConfiguration.class,
        nodeDescription = "Sends RPC call to device",
        nodeDetails = "Expects messages with \"method\" and \"params\". Will forward response from device to next nodes." +
                "If the RPC call request is originated by REST API call from user, will forward the response to user immediately.",
        uiResources = {"static/rulenode/rulenode-core-config.js"},
        configDirective = "tbActionNodeRpcRequestConfig",
        icon = "call_made"
)
public class TbSendRPCRequestNode implements TbNode {

    private static final ObjectMapper json = new ObjectMapper();
    private Random random = new Random();
    private Gson gson = new Gson();
    private JsonParser jsonParser = new JsonParser();
    private TbSendRpcRequestNodeConfiguration config;

    @Override
    public void init(TbContext ctx, TbNodeConfiguration configuration) throws TbNodeException {
        this.config = TbNodeUtils.convert(configuration, TbSendRpcRequestNodeConfiguration.class);
    }

    @Override
    public void onMsg(TbContext ctx, TbMsg msg) {
        JsonObject json = jsonParser.parse(msg.getData()).getAsJsonObject();
        String tmp;
        if (msg.getOriginator().getEntityType() != EntityType.DEVICE) {
            ctx.tellFailure(msg, new RuntimeException("Message originator is not a device entity!"));
        } else if (!json.has("method")) {
            ctx.tellFailure(msg, new RuntimeException("Method is not present in the message!"));
        } else if (!json.has("params")) {
            ctx.tellFailure(msg, new RuntimeException("Params are not present in the message!"));
        } else {
            int requestId = json.has("requestId") ? json.get("requestId").getAsInt() : random.nextInt();
            boolean restApiCall = msg.getType().equals(DataConstants.RPC_CALL_FROM_SERVER_TO_DEVICE);

            tmp = msg.getMetaData().getValue("oneway");
            boolean oneway = !StringUtils.isEmpty(tmp) && Boolean.parseBoolean(tmp);

            tmp = msg.getMetaData().getValue("requestUUID");
            UUID requestUUID = !StringUtils.isEmpty(tmp) ? UUID.fromString(tmp) : UUIDs.timeBased();
            tmp = msg.getMetaData().getValue("originServiceId");
            String originServiceId = !StringUtils.isEmpty(tmp) ? tmp : null;

            tmp = msg.getMetaData().getValue("expirationTime");
            long expirationTime = !StringUtils.isEmpty(tmp) ? Long.parseLong(tmp) : (System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(config.getTimeoutInSeconds()));

            String params;
            JsonElement paramsEl = json.get("params");
            if (paramsEl.isJsonPrimitive()) {
                params = paramsEl.getAsString();
            } else {
                params = gson.toJson(paramsEl);
            }

            RuleEngineDeviceRpcRequest request = RuleEngineDeviceRpcRequest.builder()
                    .oneway(oneway)
                    .method(json.get("method").getAsString())
                    .body(params)
                    .tenantId(ctx.getTenantId())
                    .deviceId(new DeviceId(msg.getOriginator().getId()))
                    .requestId(requestId)
                    .requestUUID(requestUUID)
                    .originServiceId(originServiceId)
                    .expirationTime(expirationTime)
                    .restApiCall(restApiCall)
                    .build();

            EdgeId edgeId = findRelatedEdgeId(ctx, msg);
            if (edgeId != null) {
                sendRpcRequestToEdgeDevice(ctx, msg, edgeId, request);
            } else {
                ctx.getRpcService().sendRpcRequestToDevice(request, ruleEngineDeviceRpcResponse -> {
                    if (!ruleEngineDeviceRpcResponse.getError().isPresent()) {
                        TbMsg next = ctx.newMsg(msg.getQueueName(), msg.getType(), msg.getOriginator(), msg.getMetaData(), ruleEngineDeviceRpcResponse.getResponse().orElse("{}"));
                        ctx.enqueueForTellNext(next, TbRelationTypes.SUCCESS);
                    } else {
                        TbMsg next = ctx.newMsg(msg.getQueueName(), msg.getType(), msg.getOriginator(), msg.getMetaData(), wrap("error", ruleEngineDeviceRpcResponse.getError().get().name()));
                        ctx.tellFailure(next, new RuntimeException(ruleEngineDeviceRpcResponse.getError().get().name()));
                    }
                });
            }
            ctx.ack(msg);
        }
    }

    private EdgeId findRelatedEdgeId(TbContext ctx, TbMsg msg) {
        List<EntityRelation> result =
                ctx.getRelationService().findByToAndType(ctx.getTenantId(), msg.getOriginator(), EntityRelation.EDGE_TYPE, RelationTypeGroup.COMMON);
        if (result != null && result.size() > 0) {
            return new EdgeId(result.get(0).getFrom().getId());
        } else {
            return null;
        }
    }

    private void sendRpcRequestToEdgeDevice(TbContext ctx, TbMsg msg, EdgeId edgeId, RuleEngineDeviceRpcRequest request) {
        EdgeEvent edgeEvent = new EdgeEvent();
        edgeEvent.setTenantId(ctx.getTenantId());
        edgeEvent.setEdgeEventAction(ActionType.RPC_CALL.name());
        edgeEvent.setEntityId(request.getDeviceId().getId());
        edgeEvent.setEdgeEventType(EdgeEventType.DEVICE);
        edgeEvent.setEntityBody(json.valueToTree(request));
        edgeEvent.setEdgeId(edgeId);
        ListenableFuture<EdgeEvent> saveFuture = ctx.getEdgeEventService().saveAsync(edgeEvent);
        Futures.addCallback(saveFuture, new FutureCallback<EdgeEvent>() {
            @Override
            public void onSuccess(@Nullable EdgeEvent event) {
                ctx.tellSuccess(msg);
            }

            @Override
            public void onFailure(Throwable th) {
                log.error("Could not save edge event", th);
                ctx.tellFailure(msg, th);
            }
        }, ctx.getDbCallbackExecutor());
    }

    @Override
    public void destroy() {
    }

    private String wrap(String name, String body) {
        JsonObject json = new JsonObject();
        json.addProperty(name, body);
        return gson.toJson(json);
    }

}
