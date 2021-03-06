/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2011, Red Hat, Inc., and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */

package org.jboss.as.controller;

import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.FAILURE_DESCRIPTION;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.jboss.as.controller.descriptions.DefaultOperationDescriptionProvider;
import org.jboss.as.controller.descriptions.DescriptionProvider;
import org.jboss.as.controller.descriptions.ModelDescriptionConstants;
import org.jboss.as.controller.descriptions.ResourceDescriptionResolver;
import org.jboss.as.controller.descriptions.common.ControllerResolver;
import org.jboss.as.controller.logging.ControllerLogger;
import org.jboss.as.controller.operations.MultistepUtil;
import org.jboss.as.controller.registry.OperationEntry;
import org.jboss.dmr.ModelNode;
import org.jboss.dmr.ModelType;

/**
 * Handler for the "composite" operation; i.e. one that includes one or more child operations
 * as steps.
 *
 * @author <a href="mailto:david.lloyd@redhat.com">David M. Lloyd</a>
 * @author Brian Stansberry (c) 2011 Red Hat Inc.
 */
public class CompositeOperationHandler implements OperationStepHandler {

    @Deprecated
    public static final OperationContext.AttachmentKey<Boolean> DOMAIN_EXECUTION_KEY = OperationContext.AttachmentKey.create(Boolean.class);

    public static final CompositeOperationHandler INSTANCE = new CompositeOperationHandler();
    public static final String NAME = ModelDescriptionConstants.COMPOSITE;

    /** Gets the failure message used for reporting a rollback with no failure message in a step */
    public static String getUnexplainedFailureMessage() {
        return ControllerLogger.ROOT_LOGGER.compositeOperationRolledBack();
    }

    private static final AttributeDefinition STEPS = new PrimitiveListAttributeDefinition.Builder(ModelDescriptionConstants.STEPS, ModelType.OBJECT)
            .build();

    public static final OperationDefinition DEFINITION = new WFLY1316HackOperationDefinitionBuilder(NAME, ControllerResolver.getResolver("root"))
        .addParameter(STEPS)
        .setReplyType(ModelType.OBJECT)
        .setPrivateEntry()
        .build();

    protected CompositeOperationHandler() {
    }

    public final void execute(final OperationContext context, final ModelNode operation) throws OperationFailedException {
        STEPS.validateOperation(operation);

        final ModelNode responseMap = context.getResult().setEmptyObject();

        // Add a step to the OC for each element in the "steps" param.
        final List<ModelNode> list = operation.get(ModelDescriptionConstants.STEPS).asList();
        Map<String, ModelNode> operationMap = new LinkedHashMap<>();
        final Map<String, ModelNode> addedResponses = new LinkedHashMap<>();
        final int size = list.size();
        for (int i = 0; i < size; i++) {
            String stepName = "step-" + (i+1);

            operationMap.put(stepName, list.get(i));

            // This makes the result steps appear in the correct order
            ModelNode stepResp = responseMap.get(stepName);
            addedResponses.put(stepName, stepResp);
        }

        MultistepUtil.recordOperationSteps(context, operationMap, addedResponses, getOperationHandlerResolver());

        context.completeStep(new OperationContext.RollbackHandler() {
            @Override
            public void handleRollback(OperationContext context, ModelNode operation) {

                // don't override useful failure information in the domain
                // or any existing failure message
                if (context.getAttachment(DOMAIN_EXECUTION_KEY) != null || context.hasFailureDescription()) {
                    return;
                }

                final ModelNode failureMsg = new ModelNode();
                for (int i = 0; i < size; i++) {
                    String stepName = "step-" + (i+1);
                    ModelNode stepResponse = responseMap.get(stepName);
                    if (stepResponse.hasDefined(FAILURE_DESCRIPTION)) {
                        failureMsg.get(ControllerLogger.ROOT_LOGGER.compositeOperationFailed(), ControllerLogger.ROOT_LOGGER.operation(stepName)).set(stepResponse.get(FAILURE_DESCRIPTION));
                    }
                }
                if (!failureMsg.isDefined()) {
                    failureMsg.set(getUnexplainedFailureMessage());
                }
                context.getFailureDescription().set(failureMsg);
            }
        });
    }

    protected MultistepUtil.OperationHandlerResolver getOperationHandlerResolver() {
        return MultistepUtil.OperationHandlerResolver.DEFAULT;
    }

    private static class WFLY1316HackOperationDefinitionBuilder extends SimpleOperationDefinitionBuilder {

        public WFLY1316HackOperationDefinitionBuilder(String name, ResourceDescriptionResolver resolver) {
            super(name, resolver);
        }

        @Override
        protected SimpleOperationDefinition internalBuild(final ResourceDescriptionResolver resolver, final ResourceDescriptionResolver attributeResolver) {
            // Make this a bit more robust in case WFLY-1315 is fixed but this hack gets left around
            if (entryType != OperationEntry.EntryType.PRIVATE) {
                return super.internalBuild(resolver, attributeResolver);
            }

            return new SimpleOperationDefinition(name, resolver, attributeResolver, entryType, flags, replyType, replyValueType, replyAllowNull, deprecationData, replyParameters, parameters) {
                /**
                 * Override the superclass behavior to go ahead and provide a description even though we are EntryType.PRIVATE
                 *
                 * {@inheritDoc}
                 */
                @Override
                public DescriptionProvider getDescriptionProvider() {
                    return new DescriptionProvider() {
                        @Override
                        public ModelNode getModelDescription(Locale locale) {
                            return new DefaultOperationDescriptionProvider(getName(), resolver, attributeResolver, replyType, replyValueType, replyAllowNull, deprecationData, replyParameters, parameters, accessConstraints).getModelDescription(locale);
                        }
                    };
                }
            };
        }
    }
}
