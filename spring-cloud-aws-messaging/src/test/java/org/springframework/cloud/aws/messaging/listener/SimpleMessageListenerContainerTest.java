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

package org.springframework.cloud.aws.messaging.listener;

import com.amazonaws.services.sqs.AmazonSQS;
import com.amazonaws.services.sqs.AmazonSQSAsync;
import com.amazonaws.services.sqs.model.GetQueueUrlRequest;
import com.amazonaws.services.sqs.model.GetQueueUrlResult;
import com.amazonaws.services.sqs.model.Message;
import com.amazonaws.services.sqs.model.MessageAttributeValue;
import com.amazonaws.services.sqs.model.ReceiveMessageRequest;
import com.amazonaws.services.sqs.model.ReceiveMessageResult;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;
import org.springframework.cloud.aws.messaging.config.annotation.EnableSqs;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.StaticApplicationContext;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.messaging.MessageHeaders;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.util.MimeType;

import java.nio.charset.Charset;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.initMocks;

/**
 * @author Agim Emruli
 * @author Alain Sahli
 * @since 1.0
 */
public class SimpleMessageListenerContainerTest {

	@Captor
	private ArgumentCaptor<org.springframework.messaging.Message<String>> stringMessageCaptor;

	@Before
	public void setUp() throws Exception {
		initMocks(this);
	}

	@Test
	public void testWithDefaultTaskExecutorAndNoBeanName() throws Exception {
		SimpleMessageListenerContainer container = new SimpleMessageListenerContainer();

		container.setAmazonSqs(mock(AmazonSQSAsync.class));
		container.setMessageHandler(mock(QueueMessageHandler.class));

		container.afterPropertiesSet();

		ThreadPoolTaskExecutor taskExecutor = (ThreadPoolTaskExecutor) container.getTaskExecutor();
		assertNotNull(taskExecutor);
		assertEquals(SimpleMessageListenerContainer.class.getSimpleName() + "-", taskExecutor.getThreadNamePrefix());
	}

	@Test
	public void testWithDefaultTaskExecutorAndBeanName() throws Exception {
		SimpleMessageListenerContainer container = new SimpleMessageListenerContainer();

		container.setAmazonSqs(mock(AmazonSQSAsync.class));
		container.setMessageHandler(mock(QueueMessageHandler.class));
		container.setBeanName("testContainerName");
		container.afterPropertiesSet();

		ThreadPoolTaskExecutor taskExecutor = (ThreadPoolTaskExecutor) container.getTaskExecutor();
		assertNotNull(taskExecutor);
		assertEquals("testContainerName-", taskExecutor.getThreadNamePrefix());
	}

	@Test
	public void testCustomTaskExecutor() throws Exception {
		SimpleMessageListenerContainer container = new SimpleMessageListenerContainer();
		SimpleAsyncTaskExecutor taskExecutor = new SimpleAsyncTaskExecutor();
		container.setTaskExecutor(taskExecutor);

		container.setAmazonSqs(mock(AmazonSQSAsync.class));
		container.setMessageHandler(mock(QueueMessageHandler.class));
		container.setBeanName("testContainerName");
		container.afterPropertiesSet();

		assertEquals(taskExecutor, container.getTaskExecutor());
	}

	@Test
	public void testSimpleReceiveMessage() throws Exception {
		SimpleMessageListenerContainer container = new SimpleMessageListenerContainer();

		AmazonSQSAsync sqs = mock(AmazonSQSAsync.class);
		container.setAmazonSqs(sqs);


		final CountDownLatch countDownLatch = new CountDownLatch(1);
		QueueMessageHandler messageHandler = new QueueMessageHandler() {

			@Override
			public void handleMessage(org.springframework.messaging.Message<?> message) throws MessagingException {
				assertEquals("messageContent", message.getPayload());
				countDownLatch.countDown();
			}
		};
		container.setMessageHandler(messageHandler);
		StaticApplicationContext applicationContext = new StaticApplicationContext();
		applicationContext.registerSingleton("testMessageListener", TestMessageListener.class);
		messageHandler.setApplicationContext(applicationContext);
		container.setBeanName("testContainerName");
		messageHandler.afterPropertiesSet();

		when(sqs.getQueueUrl(new GetQueueUrlRequest("testQueue"))).thenReturn(new GetQueueUrlResult().
				withQueueUrl("http://testQueue.amazonaws.com"));

		container.afterPropertiesSet();

		when(sqs.receiveMessage(new ReceiveMessageRequest("http://testQueue.amazonaws.com").withAttributeNames("All").
				withMessageAttributeNames(MessageHeaders.CONTENT_TYPE))).
				thenReturn(new ReceiveMessageResult().withMessages(new Message().withBody("messageContent"),
						new Message().withBody("messageContent"))).
				thenReturn(new ReceiveMessageResult());

		container.start();

		assertTrue(countDownLatch.await(1, TimeUnit.SECONDS));

		container.stop();
	}

	@Test
	public void testContainerDoesNotProcessMessageAfterBeingStopped() throws Exception {
		final SimpleMessageListenerContainer container = new SimpleMessageListenerContainer();
		SimpleAsyncTaskExecutor taskExecutor = new SimpleAsyncTaskExecutor();
		container.setTaskExecutor(taskExecutor);

		AmazonSQSAsync sqs = mock(AmazonSQSAsync.class);
		container.setAmazonSqs(sqs);

		QueueMessageHandler messageHandler = new QueueMessageHandler() {

			@Override
			public void handleMessage(org.springframework.messaging.Message<?> message) throws MessagingException {
				fail("Should not have been called");
			}
		};
		container.setMessageHandler(messageHandler);
		container.setBeanName("testContainerName");

		when(sqs.getQueueUrl(new GetQueueUrlRequest("testQueue"))).thenReturn(new GetQueueUrlResult().
				withQueueUrl("http://testQueue.amazonaws.com"));

		container.afterPropertiesSet();

		when(sqs.receiveMessage(new ReceiveMessageRequest("http://testQueue.amazonaws.com"))).
				thenAnswer(new Answer<ReceiveMessageResult>() {

					@Override
					public ReceiveMessageResult answer(InvocationOnMock invocation) throws Throwable {
						container.stop();
						return new ReceiveMessageResult().withMessages(new Message().withBody("test"));
					}
				});

		container.start();
	}

	@Test
	public void testListenerMethodThrowsException() throws Exception {
		SimpleMessageListenerContainer container = new SimpleMessageListenerContainer() {

			@Override
			protected void handleError(Throwable throwable) {
				assertNotNull(throwable);
				assertTrue(IllegalArgumentException.class.isInstance(throwable));
				super.stop();
			}
		};
		AsyncTaskExecutor taskExecutor = new SimpleAsyncTaskExecutor();
		container.setTaskExecutor(taskExecutor);

		AmazonSQSAsync sqs = mock(AmazonSQSAsync.class);
		container.setAmazonSqs(sqs);

		QueueMessageHandler messageHandler = new QueueMessageHandler() {

			@Override
			public void handleMessage(org.springframework.messaging.Message<?> message) throws MessagingException {
				throw new IllegalArgumentException("expected exception");
			}
		};
		container.setMessageHandler(messageHandler);
		container.setBeanName("testContainerName");

		when(sqs.getQueueUrl(new GetQueueUrlRequest("testQueue"))).thenReturn(new GetQueueUrlResult().
				withQueueUrl("http://testQueue.amazonaws.com"));

		container.afterPropertiesSet();

		when(sqs.receiveMessage(new ReceiveMessageRequest("http://testQueue.amazonaws.com"))).
				thenReturn(new ReceiveMessageResult().withMessages(new Message().withBody("messageContent"),
						new Message().withBody("messageContent"))).
				thenReturn(new ReceiveMessageResult());

		container.start();
	}

	@Test
	public void listener_withMultipleMessageHandlers_shouldBeCalled() throws Exception {
		final CountDownLatch countDownLatch = new CountDownLatch(2);
		SimpleMessageListenerContainer container = new SimpleMessageListenerContainer() {

			@Override
			protected void executeMessage(org.springframework.messaging.Message<String> stringMessage) {
				super.executeMessage(stringMessage);
				countDownLatch.countDown();
			}
		};
		AmazonSQSAsync sqs = mock(AmazonSQSAsync.class);
		container.setAmazonSqs(sqs);

		QueueMessageHandler messageHandler = new QueueMessageHandler();
		container.setMessageHandler(messageHandler);
		StaticApplicationContext applicationContext = new StaticApplicationContext();
		applicationContext.registerSingleton("testMessageListener", TestMessageListener.class);
		applicationContext.registerSingleton("anotherTestMessageListener", AnotherTestMessageListener.class);

		when(sqs.getQueueUrl(new GetQueueUrlRequest("testQueue"))).thenReturn(new GetQueueUrlResult().
				withQueueUrl("http://testQueue.amazonaws.com"));
		when(sqs.getQueueUrl(new GetQueueUrlRequest("anotherTestQueue"))).thenReturn(new GetQueueUrlResult().
				withQueueUrl("http://anotherTestQueue.amazonaws.com"));

		messageHandler.setApplicationContext(applicationContext);
		messageHandler.afterPropertiesSet();
		container.afterPropertiesSet();

		when(sqs.receiveMessage(new ReceiveMessageRequest("http://testQueue.amazonaws.com").withAttributeNames("All").
				withMessageAttributeNames(MessageHeaders.CONTENT_TYPE))).
				thenReturn(new ReceiveMessageResult().withMessages(new Message().withBody("messageContent"))).
				thenReturn(new ReceiveMessageResult());
		when(sqs.receiveMessage(new ReceiveMessageRequest("http://anotherTestQueue.amazonaws.com").withAttributeNames("All").
				withMessageAttributeNames(MessageHeaders.CONTENT_TYPE))).
				thenReturn(new ReceiveMessageResult().withMessages(new Message().withBody("anotherMessageContent"))).
				thenReturn(new ReceiveMessageResult());

		container.start();

		assertTrue(countDownLatch.await(2L, TimeUnit.SECONDS));
		container.stop();
		assertEquals("messageContent", applicationContext.getBean(TestMessageListener.class).getMessage());
		assertEquals("anotherMessageContent", applicationContext.getBean(AnotherTestMessageListener.class).getMessage());
	}

	@Test
	public void messageExecutor_withMessageWithAttributes_shouldPassThemAsHeaders() throws Exception {
		// Arrange
		final CountDownLatch countDownLatch = new CountDownLatch(1);
		SimpleMessageListenerContainer container = new SimpleMessageListenerContainer() {

			@Override
			protected void executeMessage(org.springframework.messaging.Message<String> stringMessage) {
				super.executeMessage(stringMessage);
				countDownLatch.countDown();
			}
		};

		AmazonSQSAsync sqs = mock(AmazonSQSAsync.class);
		container.setAmazonSqs(sqs);

		QueueMessageHandler messageHandler = spy(new QueueMessageHandler());
		container.setMessageHandler(messageHandler);

		StaticApplicationContext applicationContext = new StaticApplicationContext();
		applicationContext.registerSingleton("testMessageListener", TestMessageListener.class);

		when(sqs.getQueueUrl(new GetQueueUrlRequest("testQueue"))).thenReturn(new GetQueueUrlResult().
				withQueueUrl("http://testQueue.amazonaws.com"));

		messageHandler.setApplicationContext(applicationContext);
		messageHandler.afterPropertiesSet();
		container.afterPropertiesSet();

		when(sqs.receiveMessage(new ReceiveMessageRequest("http://testQueue.amazonaws.com").withAttributeNames("All").withMessageAttributeNames(MessageHeaders.CONTENT_TYPE))).
				thenReturn(new ReceiveMessageResult().withMessages(new Message().withBody("messageContent").withAttributes(Collections.singletonMap("SenderId", "ID")))).
				thenReturn(new ReceiveMessageResult());

		// Act
		container.start();

		// Assert
		assertTrue(countDownLatch.await(2L, TimeUnit.SECONDS));
		container.stop();

		verify(messageHandler).handleMessage(this.stringMessageCaptor.capture());
		assertEquals("ID", this.stringMessageCaptor.getValue().getHeaders().get("SenderId"));

	}

	@Test
	public void doDestroy_whenContainerCallsDestroy_DestroysDefaultTaskExecutor() throws Exception {
		// Arrange
		SimpleMessageListenerContainer simpleMessageListenerContainer = new SimpleMessageListenerContainer();
		AmazonSQSAsync sqs = mock(AmazonSQSAsync.class);
		simpleMessageListenerContainer.setAmazonSqs(sqs);
		QueueMessageHandler messageHandler = mock(QueueMessageHandler.class);
		simpleMessageListenerContainer.setMessageHandler(messageHandler);
		simpleMessageListenerContainer.afterPropertiesSet();
		simpleMessageListenerContainer.start();

		// Act
		simpleMessageListenerContainer.destroy();

		// Assert
		assertTrue(((ThreadPoolTaskExecutor) simpleMessageListenerContainer.getTaskExecutor()).getThreadPoolExecutor().isTerminated());
	}

	@Test
	public void afterPropertiesSet_whenCalled_taskExecutorIsActive() throws Exception {
		// Arrange
		SimpleMessageListenerContainer simpleMessageListenerContainer = new SimpleMessageListenerContainer();
		AmazonSQSAsync sqs = mock(AmazonSQSAsync.class);
		simpleMessageListenerContainer.setAmazonSqs(sqs);
		QueueMessageHandler messageHandler = mock(QueueMessageHandler.class);
		simpleMessageListenerContainer.setMessageHandler(messageHandler);

		// Act
		simpleMessageListenerContainer.afterPropertiesSet();

		// Assert
		assertFalse(((ThreadPoolTaskExecutor) simpleMessageListenerContainer.getTaskExecutor()).getThreadPoolExecutor().isTerminated());
	}

	@Test
	public void messageExecutor_messageWithMimeTypeMessageAttribute_shouldSetItAsHeader() throws Exception {
		// Arrange
		final CountDownLatch countDownLatch = new CountDownLatch(1);
		SimpleMessageListenerContainer container = new SimpleMessageListenerContainer() {

			@Override
			protected void executeMessage(org.springframework.messaging.Message<String> stringMessage) {
				super.executeMessage(stringMessage);
				countDownLatch.countDown();
			}
		};

		AmazonSQSAsync sqs = mock(AmazonSQSAsync.class);
		container.setAmazonSqs(sqs);

		QueueMessageHandler messageHandler = spy(new QueueMessageHandler());
		container.setMessageHandler(messageHandler);

		StaticApplicationContext applicationContext = new StaticApplicationContext();
		applicationContext.registerSingleton("testMessageListener", TestMessageListener.class);

		when(sqs.getQueueUrl(new GetQueueUrlRequest("testQueue"))).thenReturn(new GetQueueUrlResult().
				withQueueUrl("http://testQueue.amazonaws.com"));

		messageHandler.setApplicationContext(applicationContext);
		messageHandler.afterPropertiesSet();
		container.afterPropertiesSet();

		MimeType mimeType = new MimeType("text", "plain", Charset.forName("UTF-8"));
		when(sqs.receiveMessage(new ReceiveMessageRequest("http://testQueue.amazonaws.com").withAttributeNames("All").withMessageAttributeNames(MessageHeaders.CONTENT_TYPE))).
				thenReturn(new ReceiveMessageResult().withMessages(new Message().withBody("messageContent").
						withAttributes(Collections.singletonMap("SenderId", "ID")).
						withMessageAttributes(Collections.singletonMap(MessageHeaders.CONTENT_TYPE, new MessageAttributeValue().withDataType("String").
								withStringValue(mimeType.toString()))))).
				thenReturn(new ReceiveMessageResult());

		// Act
		container.start();

		// Assert
		assertTrue(countDownLatch.await(2L, TimeUnit.SECONDS));
		container.stop();

		verify(messageHandler).handleMessage(this.stringMessageCaptor.capture());
		assertEquals(mimeType, this.stringMessageCaptor.getValue().getHeaders().get(MessageHeaders.CONTENT_TYPE));
	}

	@Test
	public void queueMessageHandler_withJavaConfig_shouldScanTheListenerMethods() throws Exception {
		// Arrange & Act
		AnnotationConfigApplicationContext applicationContext = new AnnotationConfigApplicationContext(SqsTestConfig.class, TestMessageListener.class);
		SimpleMessageListenerContainer simpleMessageListenerContainer = applicationContext.getBean(SimpleMessageListenerContainer.class);
		QueueMessageHandler queueMessageHandler = (QueueMessageHandler) ReflectionTestUtils.getField(simpleMessageListenerContainer, "messageHandler");

		// Assert
		assertEquals(1, queueMessageHandler.getHandlerMethods().size());
	}

	private static class TestMessageListener {

		private String message;

		@SuppressWarnings("UnusedDeclaration")
		@MessageMapping("testQueue")
		private void handleMessage(String message) {
			this.message = message;
		}

		public String getMessage() {
			return this.message;
		}
	}

	private static class AnotherTestMessageListener {

		private String message;

		@SuppressWarnings("UnusedDeclaration")
		@MessageMapping("anotherTestQueue")
		private void handleMessage(String message) {
			this.message = message;
		}

		public String getMessage() {
			return this.message;
		}
	}

	@Configuration
	@EnableSqs
	protected static class SqsTestConfig {

		@Bean
		public AmazonSQS amazonSQS() {
			AmazonSQS mockAmazonSQS = mock(AmazonSQS.class);
			when(mockAmazonSQS.getQueueUrl(new GetQueueUrlRequest("testQueue"))).thenReturn(new GetQueueUrlResult().
					withQueueUrl("http://testQueue.amazonaws.com"));
			return mockAmazonSQS;
		}

	}

}