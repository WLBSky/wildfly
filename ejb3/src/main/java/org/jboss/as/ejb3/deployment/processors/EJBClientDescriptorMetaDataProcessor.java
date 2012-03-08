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

package org.jboss.as.ejb3.deployment.processors;

import org.jboss.as.ee.metadata.EJBClientDescriptorMetaData;
import org.jboss.as.ee.structure.Attachments;
import org.jboss.as.ejb3.deployment.EjbDeploymentAttachmentKeys;
import org.jboss.as.ejb3.remote.DescriptorBasedEJBClientContextService;
import org.jboss.as.ejb3.remote.EJBClientClusterConfig;
import org.jboss.as.ejb3.remote.EJBClientClusterNodeConfig;
import org.jboss.as.ejb3.remote.JBossEJBClientXmlConfiguration;
import org.jboss.as.ejb3.remote.LocalEjbReceiver;
import org.jboss.as.ejb3.remote.TCCLEJBClientContextSelectorService;
import org.jboss.as.remoting.AbstractOutboundConnectionService;
import org.jboss.as.server.deployment.DeploymentPhaseContext;
import org.jboss.as.server.deployment.DeploymentUnit;
import org.jboss.as.server.deployment.DeploymentUnitProcessingException;
import org.jboss.as.server.deployment.DeploymentUnitProcessor;
import org.jboss.ejb.client.EJBClientConfiguration;
import org.jboss.logging.Logger;
import org.jboss.modules.Module;
import org.jboss.msc.service.ServiceBuilder;
import org.jboss.msc.service.ServiceName;
import org.jboss.msc.service.ServiceRegistry;
import org.jboss.msc.service.ServiceTarget;

/**
 * A deployment unit processor which processing only top level deployment units and checks for the presence
 * of a {@link Attachments#EJB_CLIENT_METADATA} key corresponding to {@link EJBClientDescriptorMetaData}, in the
 * deployment unit.
 * <p/>
 * If a {@link EJBClientDescriptorMetaData} is available then this deployment unit processor
 * creates and installs a {@link DescriptorBasedEJBClientContextService}. It then attaches the {@link org.jboss.ejb.client.EJBClientContext}
 * as an attachment to the deployment unit, under the key {@link EjbDeploymentAttachmentKeys#EJB_CLIENT_CONTEXT}
 *
 * @author Jaikiran Pai
 */
public class EJBClientDescriptorMetaDataProcessor implements DeploymentUnitProcessor {

    private static final Logger logger = Logger.getLogger(EJBClientDescriptorMetaDataProcessor.class);

    @Override
    public void deploy(DeploymentPhaseContext phaseContext) throws DeploymentUnitProcessingException {
        final DeploymentUnit deploymentUnit = phaseContext.getDeploymentUnit();
        // we only process top level deployment units
        if (deploymentUnit.getParent() != null) {
            return;
        }
        final EJBClientDescriptorMetaData ejbClientDescriptorMetaData = deploymentUnit.getAttachment(Attachments.EJB_CLIENT_METADATA);
        // no explicit EJB client configuration in this deployment, so nothing to do
        if (ejbClientDescriptorMetaData == null) {
            return;
        }
        final Module module = deploymentUnit.getAttachment(org.jboss.as.server.deployment.Attachments.MODULE);
        if (module == null) {
            return;
        }
        // install the descriptor based EJB client context service
        final ServiceName ejbClientContextServiceName = DescriptorBasedEJBClientContextService.BASE_SERVICE_NAME.append(deploymentUnit.getName());
        final ServiceTarget serviceTarget = phaseContext.getServiceTarget();
        // create the client configuration from the metadata
        final EJBClientConfiguration ejbClientConfiguration = this.createClientConfiguration(phaseContext.getServiceRegistry(), module.getClassLoader(), ejbClientDescriptorMetaData);
        // create the service
        final DescriptorBasedEJBClientContextService service = new DescriptorBasedEJBClientContextService(ejbClientConfiguration);
        // add the service
        final ServiceBuilder serviceBuilder = serviceTarget.addService(ejbClientContextServiceName, service);
        // add the remoting connection reference dependencies
        for (final String connectionRef : ejbClientDescriptorMetaData.getRemotingReceiverConnectionRefs()) {
            final ServiceName connectionDependencyService = AbstractOutboundConnectionService.OUTBOUND_CONNECTION_BASE_SERVICE_NAME.append(connectionRef);
            service.addRemotingConnectionDependency(serviceBuilder, connectionDependencyService);
        }
        // if the local receiver is enabled for this context, then add a dependency on the appropriate LocalEjbReceiver
        // service
        if (!ejbClientDescriptorMetaData.isLocalReceiverExcluded()) {
            final Boolean passByValue = ejbClientDescriptorMetaData.isLocalReceiverPassByValue();
            if (passByValue != null) {
                final ServiceName localEjbReceiverServiceName = passByValue == true ? LocalEjbReceiver.BY_VALUE_SERVICE_NAME : LocalEjbReceiver.BY_REFERENCE_SERVICE_NAME;
                serviceBuilder.addDependency(localEjbReceiverServiceName, LocalEjbReceiver.class, service.getLocalEjbReceiverInjector());
            } else {
                // setup a dependency on the default local ejb receiver service configured at the subsystem level
                serviceBuilder.addDependency(LocalEjbReceiver.DEFAULT_LOCAL_EJB_RECEIVER_SERVICE_NAME, LocalEjbReceiver.class, service.getLocalEjbReceiverInjector());
            }
        }
        serviceBuilder.addDependency(TCCLEJBClientContextSelectorService.TCCL_BASED_EJB_CLIENT_CONTEXT_SELECTOR_SERVICE_NAME);
        // install the service
        serviceBuilder.install();
        logger.debug("Deployment unit " + deploymentUnit + " will use " + ejbClientContextServiceName + " as the EJB client context service");

        // attach the service name of this EJB client context to the deployment unit
        phaseContext.addDeploymentDependency(ejbClientContextServiceName, EjbDeploymentAttachmentKeys.EJB_CLIENT_CONTEXT);
    }

    @Override
    public void undeploy(DeploymentUnit context) {

    }

    private EJBClientConfiguration createClientConfiguration(final ServiceRegistry serviceRegistry, final ClassLoader classLoader, final EJBClientDescriptorMetaData ejbClientDescriptorMetaData) {

        final JBossEJBClientXmlConfiguration ejbClientConfig = new JBossEJBClientXmlConfiguration();
        for (final EJBClientDescriptorMetaData.ClusterConfig clusterMetadata : ejbClientDescriptorMetaData.getClusterConfigs()) {
            final EJBClientClusterConfig clusterConfig = new EJBClientClusterConfig(clusterMetadata, classLoader, serviceRegistry);
            // add it to the client configuration
            ejbClientConfig.addClusterConfiguration(clusterConfig);

            for (final EJBClientDescriptorMetaData.ClusterNodeConfig nodeMetadata : clusterMetadata.getClusterNodeConfigs()) {
                final EJBClientClusterNodeConfig clusterNodeConfig = new EJBClientClusterNodeConfig(nodeMetadata, classLoader, serviceRegistry);
                clusterConfig.addClusterNode(clusterNodeConfig);
            }

        }
        return ejbClientConfig;
    }


}
