/*
 * Copyright 2013-2014 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.cloud.aws.messaging.config.xml;

import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanDefinitionHolder;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.beans.factory.support.ManagedList;
import org.springframework.beans.factory.xml.AbstractBeanDefinitionParser;
import org.springframework.beans.factory.xml.ParserContext;
import org.springframework.cloud.aws.context.config.xml.GlobalBeanDefinitionUtils;
import org.springframework.cloud.aws.messaging.core.QueueMessagingTemplate;
import org.springframework.cloud.aws.messaging.listener.QueueMessageHandler;
import org.springframework.cloud.aws.messaging.listener.SendToHandlerMethodReturnValueHandler;
import org.springframework.cloud.aws.messaging.listener.SimpleMessageListenerContainer;
import org.springframework.core.Conventions;
import org.springframework.util.StringUtils;
import org.springframework.util.xml.DomUtils;
import org.w3c.dom.Element;

import static org.springframework.cloud.aws.messaging.config.xml.BufferedSqsClientBeanDefinitionUtils.getCustomAmazonSqsClientOrDecoratedDefaultSqsClientBeanName;

/**
 * {@link org.springframework.beans.factory.xml.BeanDefinitionParser} for the &lt;annotation-driven-queue-listener/&gt;
 * element.
 *
 * @author Agim Emruli
 * @author Alain Sahli
 * @since 1.0
 */
public class AnnotationDrivenQueueListenerBeanDefinitionParser extends AbstractBeanDefinitionParser {

	@Override
	protected AbstractBeanDefinition parseInternal(Element element, ParserContext parserContext) {
		BeanDefinitionBuilder containerBuilder = BeanDefinitionBuilder.genericBeanDefinition(SimpleMessageListenerContainer.class);

		if (StringUtils.hasText(element.getAttribute("task-executor"))) {
			containerBuilder.addPropertyReference(Conventions.attributeNameToPropertyName("task-executor"), element.getAttribute("task-executor"));
		}

		if (StringUtils.hasText(element.getAttribute("max-number-of-messages"))) {
			containerBuilder.addPropertyValue(Conventions.attributeNameToPropertyName("max-number-of-messages"), element.getAttribute("max-number-of-messages"));
		}

		if (StringUtils.hasText(element.getAttribute("visibility-timeout"))) {
			containerBuilder.addPropertyValue(Conventions.attributeNameToPropertyName("visibility-timeout"), element.getAttribute("visibility-timeout"));
		}

		if (StringUtils.hasText(element.getAttribute("wait-time-out"))) {
			containerBuilder.addPropertyValue(Conventions.attributeNameToPropertyName("wait-time-out"), element.getAttribute("wait-time-out"));
		}

		if (StringUtils.hasText(element.getAttribute("auto-startup"))) {
			containerBuilder.addPropertyValue(Conventions.attributeNameToPropertyName("auto-startup"), element.getAttribute("auto-startup"));
		}

		String amazonSqsClientBeanName = getCustomAmazonSqsClientOrDecoratedDefaultSqsClientBeanName(element, parserContext);

		containerBuilder.addPropertyReference(Conventions.attributeNameToPropertyName("amazon-sqs"), amazonSqsClientBeanName);

		containerBuilder.addPropertyReference("resourceIdResolver", GlobalBeanDefinitionUtils.retrieveResourceIdResolverBeanName(parserContext.getRegistry()));
		containerBuilder.addPropertyReference("messageHandler", getMessageHandlerBeanName(element, parserContext, amazonSqsClientBeanName));

		return containerBuilder.getBeanDefinition();
	}

	@Override
	protected boolean shouldGenerateId() {
		return true;
	}

	private static String getMessageHandlerBeanName(Element element, ParserContext parserContext, String sqsClientBeanName) {
		BeanDefinitionBuilder queueMessageHandlerDefinitionBuilder = BeanDefinitionBuilder.rootBeanDefinition(QueueMessageHandler.class);

		ManagedList<?> argumentResolvers = getArgumentResolvers(element, parserContext);
		if (!argumentResolvers.isEmpty()) {
			queueMessageHandlerDefinitionBuilder.addPropertyValue("customArgumentResolvers", argumentResolvers);
		}

		ManagedList<BeanDefinition> returnValueHandlers = getReturnValueHandlers(element, parserContext);
		returnValueHandlers.add(createSendToHandlerMethodReturnValueHandlerBeanDefinition(element, parserContext, sqsClientBeanName));
		queueMessageHandlerDefinitionBuilder.addPropertyValue("customReturnValueHandlers", returnValueHandlers);

		String messageHandlerBeanName = parserContext.getReaderContext().generateBeanName(queueMessageHandlerDefinitionBuilder.getBeanDefinition());
		parserContext.getRegistry().registerBeanDefinition(messageHandlerBeanName, queueMessageHandlerDefinitionBuilder.getBeanDefinition());

		return messageHandlerBeanName;
	}

	private static AbstractBeanDefinition createSendToHandlerMethodReturnValueHandlerBeanDefinition(Element element, ParserContext parserContext, String sqsClientBeanName) {
		BeanDefinitionBuilder beanDefinitionBuilder = BeanDefinitionBuilder.rootBeanDefinition(SendToHandlerMethodReturnValueHandler.class);
		if (StringUtils.hasText(element.getAttribute("send-to-message-template"))) {
			beanDefinitionBuilder.addConstructorArgReference(element.getAttribute("send-to-message-template"));
		} else {
			// TODO consider creating a utils for setting up the queue messaging template as it also created in QueueMessagingTemplateBeanDefinitionParser
			BeanDefinitionBuilder templateBuilder = BeanDefinitionBuilder.rootBeanDefinition(QueueMessagingTemplate.class);
			templateBuilder.addConstructorArgReference(sqsClientBeanName);
			templateBuilder.addConstructorArgReference(GlobalBeanDefinitionUtils.retrieveResourceIdResolverBeanName(parserContext.getRegistry()));

			beanDefinitionBuilder.addConstructorArgValue(templateBuilder.getBeanDefinition());
		}

		return beanDefinitionBuilder.getBeanDefinition();
	}

	private static ManagedList<BeanDefinition> getArgumentResolvers(Element element, ParserContext parserContext) {
		Element resolversElement = DomUtils.getChildElementByTagName(element, "argument-resolvers");
		if (resolversElement != null) {
			return extractBeanSubElements(resolversElement, parserContext);
		} else {
			return new ManagedList<>(0);
		}
	}

	private static ManagedList<BeanDefinition> getReturnValueHandlers(Element element, ParserContext parserContext) {
		Element handlersElement = DomUtils.getChildElementByTagName(element, "return-value-handlers");
		if (handlersElement != null) {
			return extractBeanSubElements(handlersElement, parserContext);
		} else {
			return new ManagedList<>(0);
		}
	}

	private static ManagedList<BeanDefinition> extractBeanSubElements(Element parentElement, ParserContext parserContext) {
		ManagedList<BeanDefinition> list = new ManagedList<>();
		list.setSource(parserContext.extractSource(parentElement));
		for (Element beanElement : DomUtils.getChildElementsByTagName(parentElement, "bean")) {
			BeanDefinitionHolder beanDef = parserContext.getDelegate().parseBeanDefinitionElement(beanElement);
			beanDef = parserContext.getDelegate().decorateBeanDefinitionIfRequired(beanElement, beanDef);
			list.add(beanDef.getBeanDefinition());
		}
		return list;
	}
}