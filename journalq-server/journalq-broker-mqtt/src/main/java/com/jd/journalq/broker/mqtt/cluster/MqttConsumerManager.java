package com.jd.journalq.broker.mqtt.cluster;

import com.jd.journalq.broker.consumer.Consume;
import com.jd.journalq.broker.mqtt.connection.MqttConnection;
import com.jd.journalq.broker.mqtt.session.MqttSession;
import com.jd.journalq.exception.JMQException;
import com.jd.journalq.message.BrokerMessage;
import com.jd.journalq.network.session.Consumer;
import com.jd.journalq.broker.BrokerContext;
import com.jd.journalq.broker.mqtt.publish.MessagePublisher;
import com.jd.journalq.broker.mqtt.subscriptions.MqttSubscription;
import com.jd.journalq.broker.mqtt.util.PollSelector;
import com.jd.journalq.broker.mqtt.util.Selector;
import com.jd.journalq.toolkit.concurrent.NamedThreadFactory;
import com.jd.journalq.toolkit.lang.Strings;
import com.jd.journalq.toolkit.service.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * @author majun8
 */
public class MqttConsumerManager extends Service {
    private static final Logger LOG = LoggerFactory.getLogger(MqttConsumerManager.class);

    private static int CONSUME_THREAD_TOTAL = 10;
    private static int ASYNC_PUB_THREAD_TOTAL = 100;
    private static int ASYNC_ACK_THREAD_TOTAL = 50;
    private Selector selector = new PollSelector();
    private ExecutorService executorService;
    private ExecutorService asyncPublishExecutorService;
    private ExecutorService asyncAcknowledgeExecutorService;
    private ConcurrentMap<Integer, Runnable> consumeThreadMap = new ConcurrentHashMap<>();
    private ConcurrentMap<String, Runnable> clientConsumeThreadMap = new ConcurrentHashMap<>();
    private ConcurrentMap<String, Consumer> consumers = new ConcurrentHashMap<>();
    private Consume consume;
    private MqttConnectionManager connectionManager;
    private MqttSessionManager sessionManager;
    private MessagePublisher messagePublisher;

    public MqttConsumerManager(BrokerContext brokerContext, MqttConnectionManager connectionManager, MqttSessionManager sessionManager, MessagePublisher messagePublisher) {
        this.consume = brokerContext.getConsume();
        this.connectionManager = connectionManager;
        this.sessionManager = sessionManager;
        this.messagePublisher = messagePublisher;
    }

    @Override
    protected void validate() throws Exception {
        super.validate();
        executorService = Executors.newFixedThreadPool(CONSUME_THREAD_TOTAL, new NamedThreadFactory("mqtt-consume"));
        asyncPublishExecutorService = Executors.newFixedThreadPool(ASYNC_PUB_THREAD_TOTAL, new NamedThreadFactory("mqtt-async-publish"));
        asyncAcknowledgeExecutorService = Executors.newFixedThreadPool(ASYNC_ACK_THREAD_TOTAL, new NamedThreadFactory("mqtt-async-acknowledge"));
    }

    @Override
    public void start() throws Exception {
        super.start();
        for (int i = 0; i < CONSUME_THREAD_TOTAL; i++) {
            Runnable consumeTask = new ConsumeTask(Integer.toString(i));
            consumeThreadMap.put(i, consumeTask);
            executorService.execute(consumeTask);
        }
    }

    @Override
    public void stop() {
        super.stop();
        if (!executorService.isShutdown()) {
            clientConsumeThreadMap.forEach(
                    (clientID, consumeThread) -> {
                        if (consumeThread != null) {
                            ConsumeTask consumeTask = (ConsumeTask) consumeThread;
                            consumeTask.setRunning(false);
                        }
                    }
            );
            executorService.shutdownNow();
        }
    }

    public void fireConsume(String clientID) {
        MqttSession session = sessionManager.getSession(clientID);
        ConsumeTask consumeTask = selectThreadConsume(clientID, session);
        clientConsumeThreadMap.put(clientID, consumeTask);
    }

    public void stopConsume(String clientID) {
        ConsumeTask consumeTask = (ConsumeTask) clientConsumeThreadMap.get(clientID);
        if (consumeTask != null) {
            consumeTask.removeClientConsume(clientID);
            clientConsumeThreadMap.remove(clientID);
        }
        removeConsumer(clientID);
    }

    public void acknowledge(String clientID, int packageId) {
        MqttSession session = sessionManager.getSession(clientID);
        if (session != null) {
            BrokerMessage brokerMessage = session.getMessageAcknowledgedZone().acquireAcknowledgedMessage(packageId);
            if (brokerMessage != null) {
                short partition = brokerMessage.getPartition();
                long index = brokerMessage.getMsgIndexNo();
                String topic = brokerMessage.getTopic();
                Consumer consumer = getConsumer(clientID, topic);
                if (consumer != null) {
                    asyncAcknowledgeExecutorService.submit(
                            () -> commitAcknowledge(consumer, partition, index)
                    );
                }
            }
        }
    }

    private ConsumeTask selectThreadConsume(String clientID, MqttSession session) {
        int threadPos = selector.select(clientID, CONSUME_THREAD_TOTAL);

        ConsumeTask consumeTask = (ConsumeTask) consumeThreadMap.get(threadPos);
        consumeTask.addClientConsume(clientID, session);
        return consumeTask;
    }

    public Consumer getConsumer(String clientID, String topic) {
        Consumer consumer = null;
        if (connectionManager.isConnected(clientID)) {
            MqttConnection connection = connectionManager.getConnection(clientID);
            String application = connection.getApplication();
            String clientGroup = connection.getClientGroupName();
            String consumerId = connection.getConsumer(topic, application);
            if (Strings.isNullOrEmpty(consumerId) || ((consumer = consumers.get(generateConsumerId(clientID, topic, application, clientGroup))) == null)) {
                consumerId = generateConsumerId(clientID, topic, application, clientGroup);
                consumer = new Consumer();
                consumer.setId(consumerId);
                consumer.setConnectionId(connection.getId());
                consumer.setApp(application);
                consumer.setTopic(topic);
                consumer.setType(Consumer.ConsumeType.MQTT);
                Consumer oldConsumer = consumers.putIfAbsent(consumerId, consumer);
                connection.addConsumer(topic, application, consumerId);
                if (oldConsumer != null) {
                    consumer = oldConsumer;
                }
            }
        }
        return consumer;
    }

    public void removeConsumer(String clientID) {
        if (connectionManager.isConnected(clientID)) {
            MqttConnection connection = connectionManager.getConnection(clientID);
            for (String app : connection.getConsumers().keySet()) {
                ConcurrentMap<String, String> topicIds = connection.getConsumers().get(app);
                for (String topic : topicIds.keySet()) {
                    String id = topicIds.get(topic);
                    if (!Strings.isNullOrEmpty(id)) {
                        consumers.remove(id);
                        topicIds.remove(topic, id);
                    }
                }
            }
        }
    }

    private String generateConsumerId(String clientID, String topic, String application, String clientGroup) {
        return String.format("%s_consumer_%s_%s_%s", clientID, application, topic, clientGroup);
    }

    private void commitAcknowledge(Consumer consumer, short partition, long index) {
        try {
            consume.setAckIndex(consumer, partition, index);
        } catch (JMQException e) {
            e.printStackTrace();
        }
    }

    private class ConsumeTask implements Runnable {
        private String name = "consume-";
        private boolean isRunning = true;

        private ConcurrentMap<String, MqttSession> clientConsumeMap = new ConcurrentHashMap<>();

        public ConsumeTask(String name) {
            this.name = this.name + name;
        }

        public boolean isRunning() {
            return isRunning;
        }

        public void setRunning(boolean running) {
            isRunning = running;
        }

        public void addClientConsume(String clientID, MqttSession session) {
            clientConsumeMap.put(clientID, session);
        }

        public void removeClientConsume(String clientID) {
            MqttSession session = clientConsumeMap.remove(clientID);
        }

        @Override
        public void run() {
            while (isRunning) {
                try {
                    if (clientConsumeMap.size() > 0) {
                        clientConsumeMap.forEach(
                                (clientID, session) -> {
                                    Set<MqttSubscription> subscriptions = session.listSubsciptions();
                                    if (subscriptions != null && subscriptions.size() > 0) {
                                        subscriptions.forEach(
                                                subscription -> {
                                                    String topic = subscription.getTopicFilter().toString();
                                                    int qos = subscription.getRequestedQos().value();
                                                    Consumer consumer = getConsumer(clientID, topic);
                                                    if (consumer != null) {
                                                        try {
                                                            asyncPublishExecutorService.execute(
                                                                    () -> {
                                                                        try {
                                                                            messagePublisher.publish2Subscriber(
                                                                                    name,
                                                                                    clientID,
                                                                                    session,
                                                                                    consumer,
                                                                                    qos
                                                                            );
                                                                        } catch (Exception e) {
                                                                            e.printStackTrace();
                                                                        }
                                                                    }
                                                            );
                                                        } catch (Exception e) {
                                                            e.printStackTrace();
                                                        }
                                                    }
                                                }
                                        );
                                    }
                                }
                        );
                        // help cpu
                        try {
                            Thread.sleep(800);
                        } catch (InterruptedException e) {
                            LOG.warn("mqtt consumer manager thread: <{}> interrupted, exception: {}", name, e.getMessage());
                            //e.printStackTrace();
                        }
                    } else {
                        try {
                            Thread.sleep(2000);
                        } catch (InterruptedException e) {
                            LOG.warn("mqtt consumer manager thread: <{}> interrupted, exception: {}", name, e.getMessage());
                            //e.printStackTrace();
                        }
                    }
                } catch (Exception e) {
                    LOG.error("Thread: <{}>, mqtt consume client message consume error, topic: <{}>, cause: <{}>", name, e.getMessage());
                    e.printStackTrace();
                }
            }
            LOG.info("mqtt consumer manager thread: <{}> stop.", name);
        }
    }
}
