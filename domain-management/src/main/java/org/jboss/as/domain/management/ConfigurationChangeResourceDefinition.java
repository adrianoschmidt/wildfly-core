/*
 * Copyright (C) 2015 Red Hat, inc., and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston,
 * MA 02110-1301  USA
 */
package org.jboss.as.domain.management;

import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.CONFIGURATION_CHANGES;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.CORE;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.MANAGEMENT;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.OP;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.OPERATIONS;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.OP_ADDR;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.READ_RESOURCE_OPERATION;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.SERVICE;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.jboss.as.controller.AbstractAddStepHandler;
import org.jboss.as.controller.AbstractRemoveStepHandler;
import org.jboss.as.controller.AbstractRuntimeOnlyHandler;
import org.jboss.as.controller.AbstractWriteAttributeHandler;
import org.jboss.as.controller.ConfigurationChangesCollector;
import org.jboss.as.controller.OperationContext;
import org.jboss.as.controller.OperationDefinition;
import org.jboss.as.controller.OperationFailedException;
import org.jboss.as.controller.PathAddress;
import org.jboss.as.controller.PathElement;
import org.jboss.as.controller.SimpleAttributeDefinition;
import org.jboss.as.controller.SimpleAttributeDefinitionBuilder;
import org.jboss.as.controller.SimpleOperationDefinitionBuilder;
import org.jboss.as.controller.SimpleResourceDefinition;
import org.jboss.as.controller.access.Action;
import org.jboss.as.controller.access.AuthorizationResult;
import org.jboss.as.controller.descriptions.ModelDescriptionConstants;
import org.jboss.as.controller.logging.ControllerLogger;
import org.jboss.as.controller.registry.ManagementResourceRegistration;
import org.jboss.as.controller.registry.OperationEntry;
import org.jboss.as.controller.registry.Resource;
import org.jboss.as.domain.management._private.DomainManagementResolver;
import org.jboss.dmr.ModelNode;
import org.jboss.dmr.ModelType;

/**
 * Resource to list all configuration changes.
 *
 * @author <a href="mailto:ehugonne@redhat.com">Emmanuel Hugonnet</a> (c) 2015 Red Hat, inc.
 */
public class ConfigurationChangeResourceDefinition extends SimpleResourceDefinition {

    public static final SimpleAttributeDefinition MAX_HISTORY = SimpleAttributeDefinitionBuilder.create(
            ModelDescriptionConstants.MAX_HISTORY, ModelType.INT)
            .setDefaultValue(new ModelNode(10))
            .build();
    public static final PathElement PATH = PathElement.pathElement(SERVICE, CONFIGURATION_CHANGES);
    public static final ConfigurationChangeResourceDefinition INSTANCE = new ConfigurationChangeResourceDefinition();
    public static final String OPERATION_NAME = "list-changes";

    private ConfigurationChangeResourceDefinition() {
        super(new Parameters(PATH, DomainManagementResolver.getResolver(CORE, MANAGEMENT, SERVICE, CONFIGURATION_CHANGES))
                .setAddHandler(new ConfigurationChangeResourceAddHandler())
                .setRemoveHandler(new ConfigurationChangeResourceRemoveHandler()));
    }

    @Override
    public void registerOperations(ManagementResourceRegistration resourceRegistration) {
        super.registerOperations(resourceRegistration);
        resourceRegistration.registerOperationHandler(ConfigurationChangesHandler.DEFINITION, ConfigurationChangesHandler.INSTANCE);
    }

    @Override
    public void registerAttributes(ManagementResourceRegistration resourceRegistration) {
        super.registerAttributes(resourceRegistration);
        resourceRegistration.registerReadWriteAttribute(MAX_HISTORY, null, new MaxHistoryWriteHandler(ConfigurationChangesCollector.INSTANCE));
    }

    private static class ConfigurationChangeResourceAddHandler extends AbstractAddStepHandler {

        public ConfigurationChangeResourceAddHandler() {
            super(MAX_HISTORY);
        }

        @Override
        protected void populateModel(OperationContext context, ModelNode operation, Resource resource) throws OperationFailedException {
            super.populateModel(context, operation, resource);
            ModelNode maxHistory = MAX_HISTORY.resolveModelAttribute(context, operation);
            MAX_HISTORY.validateAndSet(operation, context.readResourceForUpdate(PathAddress.EMPTY_ADDRESS).getModel());
            ConfigurationChangesCollector.INSTANCE.setMaxHistory(maxHistory.asInt());
        }

    }

    private static class ConfigurationChangeResourceRemoveHandler extends AbstractRemoveStepHandler {

        public ConfigurationChangeResourceRemoveHandler() {
            ConfigurationChangesCollector.INSTANCE.deactivate();
        }
    }

    private static class MaxHistoryWriteHandler extends AbstractWriteAttributeHandler<Integer> {

        private static final MaxHistoryWriteHandler INSTANCE = new MaxHistoryWriteHandler(ConfigurationChangesCollector.INSTANCE);
        private final ConfigurationChangesCollector collector;

        private MaxHistoryWriteHandler(ConfigurationChangesCollector collector) {
            this.collector = collector;
        }

        @Override
        protected boolean applyUpdateToRuntime(OperationContext context, ModelNode operation, String attributeName, ModelNode resolvedValue, ModelNode currentValue, HandbackHolder<Integer> handbackHolder) throws OperationFailedException {
            MAX_HISTORY.validateAndSet(operation, context.readResourceForUpdate(PathAddress.EMPTY_ADDRESS).getModel());
            collector.setMaxHistory(resolvedValue.asInt());
            return true;
        }

        @Override
        protected void revertUpdateToRuntime(OperationContext context, ModelNode operation, String attributeName, ModelNode valueToRestore, ModelNode valueToRevert, Integer handback) throws OperationFailedException {
            MAX_HISTORY.validateAndSet(operation, context.readResourceForUpdate(PathAddress.EMPTY_ADDRESS).getModel());
            collector.setMaxHistory(valueToRestore.asInt());
        }
    }

    private static class ConfigurationChangesHandler extends AbstractRuntimeOnlyHandler {

        private static final OperationDefinition DEFINITION = new SimpleOperationDefinitionBuilder(OPERATION_NAME,
                DomainManagementResolver.getResolver(CORE, MANAGEMENT, SERVICE, CONFIGURATION_CHANGES))
                .setReplyType(ModelType.STRING)
                .setRuntimeOnly()
                .build();
        private static final ConfigurationChangesHandler INSTANCE = new ConfigurationChangesHandler(ConfigurationChangesCollector.INSTANCE);
        private static final Set<Action.ActionEffect> ADDRESS_EFFECT = EnumSet.of(Action.ActionEffect.ADDRESS);

        private final ConfigurationChangesCollector collector;

        private ConfigurationChangesHandler(ConfigurationChangesCollector collector) {
            this.collector = collector;
        }

        @Override
        protected void executeRuntimeStep(OperationContext context, ModelNode operation) throws OperationFailedException {
            if (collector != null) {
                ModelNode result = context.getResult().setEmptyList();
                for (ModelNode change : collector.getChanges()) {
                    ModelNode configurationChange = change.clone();
                    secureHistory(context, configurationChange);
                        result.add(configurationChange);
                }
            }
        }

        /**
         * Checks if the calling user may execute the operation. If he can then he cvan see it in the result.
         *
         * @param context
         * @param configurationChange
         * @throws OperationFailedException.
         */
        private void secureHistory(OperationContext context, ModelNode configurationChange) throws OperationFailedException {
            if (configurationChange.has(OPERATIONS)) {
                List<ModelNode> operations = configurationChange.get(OPERATIONS).asList();
                ModelNode authorizedOperations = configurationChange.get(OPERATIONS).setEmptyList();
                for (ModelNode operation : operations) {
                    authorizedOperations.add(secureOperation(context, operation));
                }
            }
        }

        /**
         * Secure the operation :
         *  - if the caller can address the resource we check if he can see the operation parameters.
         *  - otherwise we return the operation without its address and parameters.
         * @param context the operation context.
         * @param operation the operation we are securing.
         * @return the secured opreation aka trimmed of all sensitive data.
         * @throws OperationFailedException.
         */
        private ModelNode secureOperation(OperationContext context, ModelNode operation) throws OperationFailedException {
            ModelNode address = operation.get(OP_ADDR);
            ModelNode fakeOperation = new ModelNode();
            fakeOperation.get(OP).set(READ_RESOURCE_OPERATION);
            fakeOperation.get(OP_ADDR).set(address);
            AuthorizationResult authResult = context.authorize(fakeOperation, ADDRESS_EFFECT);
            if(authResult.getDecision() == AuthorizationResult.Decision.PERMIT) {
                return secureOperationParameters(context, operation);
            }
            ModelNode securedOperation = new ModelNode();
            securedOperation.get(OP).set(operation.get(OP));
            securedOperation.get(OP_ADDR).set(ControllerLogger.MGMT_OP_LOGGER.permissionDenied());
            return securedOperation;
        }

        /**
         * Checks if the calling user may execute the operation. If he may then he can see it te full operation parameters.
         * @param context the operation context.
         * @param op the operation we are securing.
         * @return the secured operation.
         * @throws OperationFailedException.
         */
        private ModelNode secureOperationParameters(OperationContext context, ModelNode op) throws OperationFailedException {
            ModelNode operation = op.clone();
            OperationEntry operationEntry = context.getRootResourceRegistration().getOperationEntry(
                    PathAddress.pathAddress(operation.get(OP_ADDR)), operation.get(OP).asString());
            Set<Action.ActionEffect> effects = getEffects(operationEntry);
            if(context.authorize(operation, effects).getDecision() == AuthorizationResult.Decision.PERMIT) {
                return operation;
            } else {
                ModelNode securedOperation = new ModelNode();
                securedOperation.get(OP).set(operation.get(OP));
                securedOperation.get(OP_ADDR).set(operation.get(OP_ADDR));
                return securedOperation;
            }
        }

        private Set<Action.ActionEffect> getEffects(OperationEntry operationEntry) {
            Set<Action.ActionEffect> effects = new HashSet<>(5);
            effects.add(Action.ActionEffect.ADDRESS);
            if (operationEntry != null) {
                effects.add(Action.ActionEffect.READ_RUNTIME);
                if (!operationEntry.getFlags().contains(OperationEntry.Flag.RUNTIME_ONLY)) {
                    effects.add(Action.ActionEffect.READ_CONFIG);
                }
                if (!operationEntry.getFlags().contains(OperationEntry.Flag.READ_ONLY)) {
                    effects.add(Action.ActionEffect.WRITE_RUNTIME);
                    if (!operationEntry.getFlags().contains(OperationEntry.Flag.RUNTIME_ONLY)) {
                        effects.add(Action.ActionEffect.WRITE_CONFIG);
                    }
                }
            }
            return effects;
        }
    }
}
