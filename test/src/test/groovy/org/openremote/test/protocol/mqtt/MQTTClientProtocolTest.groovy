/*
 * Copyright 2019, OpenRemote Inc.
 *
 * See the CONTRIBUTORS.txt file in the distribution for a
 * full listing of individual contributors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.openremote.test.protocol.mqtt

import com.hivemq.client.internal.mqtt.MqttClientConnectionConfig
import com.hivemq.client.internal.mqtt.handler.disconnect.MqttDisconnectUtil
import com.hivemq.client.internal.mqtt.mqtt3.Mqtt3AsyncClientView
import com.hivemq.client.internal.mqtt.mqtt3.Mqtt3ClientConfigView
import com.hivemq.client.mqtt.MqttClientConfig
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.X509v3CertificateBuilder
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.ContentSigner
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.bouncycastle.pkcs.PKCS10CertificationRequest
import org.bouncycastle.pkcs.jcajce.JcaPKCS10CertificationRequestBuilder
import org.bouncycastle.util.io.pem.PemObject
import org.bouncycastle.util.io.pem.PemWriter
import org.openremote.agent.protocol.mqtt.*
import org.openremote.agent.protocol.simulator.SimulatorProtocol
import org.openremote.manager.agent.AgentService
import org.openremote.manager.asset.AssetProcessingService
import org.openremote.manager.asset.AssetStorageService
import org.openremote.manager.mqtt.DefaultMQTTHandler
import org.openremote.manager.mqtt.MQTTBrokerService
import org.openremote.manager.security.KeyStoreServiceImpl
import org.openremote.manager.setup.SetupService
import org.openremote.model.Constants
import org.openremote.model.asset.Asset
import org.openremote.model.asset.UserAssetLink
import org.openremote.model.asset.agent.Agent
import org.openremote.model.asset.agent.ConnectionStatus
import org.openremote.model.asset.impl.ThingAsset
import org.openremote.model.attribute.Attribute
import org.openremote.model.attribute.AttributeEvent
import org.openremote.model.attribute.MetaItem
import org.openremote.model.auth.UsernamePassword
import org.openremote.model.query.filter.NumberPredicate
import org.openremote.model.util.UniqueIdentifierGenerator
import org.openremote.model.util.ValueUtil
import org.openremote.model.value.JsonPathFilter
import org.openremote.model.value.ValueFilter
import org.openremote.setup.integration.KeycloakTestSetup
import org.openremote.setup.integration.ManagerTestSetup
import org.openremote.test.ManagerContainerTrait
import org.opentest4j.TestAbortedException
import spock.lang.Ignore
import spock.lang.Specification
import spock.util.concurrent.PollingConditions

import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.concurrent.CopyOnWriteArrayList
import java.util.function.Consumer

import static org.openremote.manager.mqtt.MQTTBrokerService.getConnectionIDString
import static org.openremote.model.Constants.MASTER_REALM
import static org.openremote.model.value.MetaItemType.AGENT_LINK
import static org.openremote.model.value.ValueType.*

class MQTTClientProtocolTest extends Specification implements ManagerContainerTrait {

    def setupSpec() {
        startContainer(defaultConfig(), defaultServices())
    }

    def "Check MQTT client"() {
        given: "expected conditions"
        def conditions = new PollingConditions(timeout: 20, delay: 0.1)
        def managerTestSetup = container.getService(SetupService.class).getTaskOfType(ManagerTestSetup.class)
        def keycloakTestSetup = container.getService(SetupService.class).getTaskOfType(KeycloakTestSetup.class)
        def assetStorageService = container.getService(AssetStorageService.class)
        def assetProcessingService = container.getService(AssetProcessingService.class)
        def mqttBrokerService = container.getService(MQTTBrokerService.class)
        def defaultMQTTHandler = mqttBrokerService.getCustomHandlers().find {it instanceof DefaultMQTTHandler} as DefaultMQTTHandler

        when: "a hiveMQ client is created to connect to our own broker"
        List<String> failedSubs = new CopyOnWriteArrayList()
        List<MQTTMessage<String>> received = new CopyOnWriteArrayList<>()
        def clientId = "ortest1"
        def host = "localhost"
        def port = 1883
        def secure = false
        def counter = 0
        def username = "${keycloakTestSetup.realmBuilding.name}:${keycloakTestSetup.serviceUser2.username}"
        def password = keycloakTestSetup.serviceUser2.secret
        def subscriptions = [
                "${keycloakTestSetup.realmBuilding.name}/${clientId}/attribute/notes/${managerTestSetup.apartment1BathroomId}".toString(),
                "${keycloakTestSetup.realmBuilding.name}/${clientId}/attribute/notes/${managerTestSetup.apartment1HallwayId}".toString(),
                "${keycloakTestSetup.realmBuilding.name}/${clientId}/attribute/notes/${managerTestSetup.apartment1Bedroom1Id}".toString(),
                "${keycloakTestSetup.realmBuilding.name}/${clientId}/attribute/notes/${managerTestSetup.apartment1KitchenId}".toString(),
                "${keycloakTestSetup.realmBuilding.name}/${clientId}/attribute/notes/${managerTestSetup.apartment1LivingroomId}".toString()
        ]
        Consumer<MQTTMessage<String>> consumer = { it ->
            LOG.info("Received on topic=${it.topic}")
            received.add(it)
        }
        def client = new MQTT_IOClient(
                clientId,
                host,
                port,
                secure,
                false,
                new UsernamePassword(username, password),
                null,
                new MQTTLastWill("${keycloakTestSetup.realmBuilding.name}/${clientId}/$DefaultMQTTHandler.ATTRIBUTE_VALUE_WRITE_TOPIC/notes/${managerTestSetup.apartment1HallwayId}".toString(), "\"LAST WILL\"".toString(), false),
                null,
                null
        )
        client.setResubscribeIfSessionPresent(false)
        client.setTopicSubscribeFailureConsumer {
            LOG.info("Subscription failed: $it")
            failedSubs.add(it)
        }
        client.addConnectionStatusConsumer {
            LOG.info("Connection status changed: $it")
        }

        and: "subscriptions are added before connect"
        client.addMessageConsumer(subscriptions[0], consumer)

        and: "the client connects"
        client.connect()

        then: "the client should be connected"
        conditions.eventually {
            assert client.getConnectionStatus() == ConnectionStatus.CONNECTED
        }

        when: "more subscriptions are added"
        client.addMessageConsumer(subscriptions[1], consumer)
        client.addMessageConsumer(subscriptions[2], consumer)
        client.addMessageConsumer(subscriptions[3], consumer)
        client.addMessageConsumer(subscriptions[4], consumer)

        then: "all consumers should be in place bathroom sub should have failed (because user is not linked to the bathroom)"
        conditions.eventually {
            assert client.topicConsumerMap.size() == 5
            assert failedSubs.size() == 1
            assert failedSubs[0] == subscriptions[0]
            assert mqttBrokerService.getUserConnections(keycloakTestSetup.serviceUser2.id).size() == 1
            def connection = mqttBrokerService.getUserConnections(keycloakTestSetup.serviceUser2.id)[0]
            assert defaultMQTTHandler.sessionSubscriptionConsumers.containsKey(getConnectionIDString(connection))
            assert defaultMQTTHandler.sessionSubscriptionConsumers.get(getConnectionIDString(connection)).size() == 4
        }

        and: "subscribed attributes are updated"
        assetProcessingService.sendAttributeEvent(new AttributeEvent(managerTestSetup.apartment1BathroomId, Asset.NOTES, Integer.toString(counter++)))
        assetProcessingService.sendAttributeEvent(new AttributeEvent(managerTestSetup.apartment1HallwayId, Asset.NOTES, Integer.toString(counter++)))
        assetProcessingService.sendAttributeEvent(new AttributeEvent(managerTestSetup.apartment1Bedroom1Id, Asset.NOTES, Integer.toString(counter++)))
        assetProcessingService.sendAttributeEvent(new AttributeEvent(managerTestSetup.apartment1KitchenId, Asset.NOTES, Integer.toString(counter++)))
        assetProcessingService.sendAttributeEvent(new AttributeEvent(managerTestSetup.apartment1LivingroomId, Asset.NOTES, Integer.toString(counter++)))

        then: "4 messages should have been received"
        conditions.eventually {
            assert received.size() == 4
            assert received.any {it.topic == subscriptions[1] && ValueUtil.parse(it.payload, AttributeEvent.class).get().value.orElse(null) == "1"}
            assert received.any {it.topic == subscriptions[2] && ValueUtil.parse(it.payload, AttributeEvent.class).get().value.orElse(null) == "2"}
            assert received.any {it.topic == subscriptions[3] && ValueUtil.parse(it.payload, AttributeEvent.class).get().value.orElse(null) == "3"}
            assert received.any {it.topic == subscriptions[4] && ValueUtil.parse(it.payload, AttributeEvent.class).get().value.orElse(null) == "4"}
        }

        when: "the user asset links are updated to include the bathroom"
        def existingConnection = mqttBrokerService.getUserConnections(keycloakTestSetup.serviceUser2.id)[0]
        assetStorageService.storeUserAssetLinks(List.of(
            new UserAssetLink(keycloakTestSetup.realmBuilding.getName(),
                    keycloakTestSetup.serviceUser2.getId(),
                    managerTestSetup.apartment1BathroomId)
        ))

        then: "the client should have disconnected and reconnected"
        conditions.eventually {
            assert client.getConnectionStatus() == ConnectionStatus.CONNECTED
            assert !mqttBrokerService.getUserConnections(keycloakTestSetup.serviceUser2.id).isEmpty()
            assert mqttBrokerService.getUserConnections(keycloakTestSetup.serviceUser2.id)[0] != existingConnection
        }

        then: "all subscriptions including the bathroom should be in place"
        conditions.eventually {
            assert client.topicConsumerMap.size() == 5
            assert client.topicConsumerMap.keySet().stream().allMatch {subscriptions.contains(it)}
            assert mqttBrokerService.getUserConnections(keycloakTestSetup.serviceUser2.id).size() == 1
            def connection = mqttBrokerService.getUserConnections(keycloakTestSetup.serviceUser2.id)[0]
            assert defaultMQTTHandler.sessionSubscriptionConsumers.containsKey(getConnectionIDString(connection))
            assert defaultMQTTHandler.sessionSubscriptionConsumers.get(getConnectionIDString(connection)).size() == 5
        }

        when: "subscribed attributes are updated"
        counter = 0
        received.clear()
        assetProcessingService.sendAttributeEvent(new AttributeEvent(managerTestSetup.apartment1BathroomId, Asset.NOTES, Integer.toString(counter++)))
        assetProcessingService.sendAttributeEvent(new AttributeEvent(managerTestSetup.apartment1HallwayId, Asset.NOTES, Integer.toString(counter++)))
        assetProcessingService.sendAttributeEvent(new AttributeEvent(managerTestSetup.apartment1Bedroom1Id, Asset.NOTES, Integer.toString(counter++)))
        assetProcessingService.sendAttributeEvent(new AttributeEvent(managerTestSetup.apartment1KitchenId, Asset.NOTES, Integer.toString(counter++)))
        assetProcessingService.sendAttributeEvent(new AttributeEvent(managerTestSetup.apartment1LivingroomId, Asset.NOTES, Integer.toString(counter++)))

        then: "5 messages should have been received"
        conditions.eventually {
            assert received.size() == 5
            assert received.any {it.topic == subscriptions[0] && ValueUtil.parse(it.payload, AttributeEvent.class).get().value.orElse(null) == "0"}
            assert received.any {it.topic == subscriptions[1] && ValueUtil.parse(it.payload, AttributeEvent.class).get().value.orElse(null) == "1"}
            assert received.any {it.topic == subscriptions[2] && ValueUtil.parse(it.payload, AttributeEvent.class).get().value.orElse(null) == "2"}
            assert received.any {it.topic == subscriptions[3] && ValueUtil.parse(it.payload, AttributeEvent.class).get().value.orElse(null) == "3"}
            assert received.any {it.topic == subscriptions[4] && ValueUtil.parse(it.payload, AttributeEvent.class).get().value.orElse(null) == "4"}
        }

        when: "the client is disconnected due to a connection error"
        existingConnection = mqttBrokerService.getUserConnections(keycloakTestSetup.serviceUser2.id)[0]
        received.clear()
        MqttDisconnectUtil.close(((MqttClientConnectionConfig)((MqttClientConfig)((Mqtt3ClientConfigView)((Mqtt3AsyncClientView)client.client).clientConfig).delegate).connectionConfig.get()).channel, "Connection Error")

        then: "the last will message should have been sent"
        conditions.eventually {
            def hallway = assetStorageService.find(managerTestSetup.apartment1HallwayId)
            assert hallway.getAttribute(Asset.NOTES).flatMap{it.value}.orElse("") == "LAST WILL"
        }

        then: "the client should reconnect"
        conditions.eventually {
            assert client.getConnectionStatus() == ConnectionStatus.CONNECTED
            assert !mqttBrokerService.getUserConnections(keycloakTestSetup.serviceUser2.id).isEmpty()
            assert mqttBrokerService.getUserConnections(keycloakTestSetup.serviceUser2.id)[0] !== existingConnection
        }


        then: "all subscriptions including the bathroom should be in place"
        conditions.eventually {
            assert client.topicConsumerMap.size() == 5
            assert client.topicConsumerMap.keySet().stream().allMatch {subscriptions.contains(it)}
            assert mqttBrokerService.getUserConnections(keycloakTestSetup.serviceUser2.id).size() == 1
            def connection = mqttBrokerService.getUserConnections(keycloakTestSetup.serviceUser2.id)[0]
            assert defaultMQTTHandler.sessionSubscriptionConsumers.containsKey(getConnectionIDString(connection))
            assert defaultMQTTHandler.sessionSubscriptionConsumers.get(getConnectionIDString(connection)).size() == 5
        }

        when: "subscribed attributes are updated"
        counter = 0
        received.clear()
        assetProcessingService.sendAttributeEvent(new AttributeEvent(managerTestSetup.apartment1BathroomId, Asset.NOTES, Integer.toString(counter++)))
        assetProcessingService.sendAttributeEvent(new AttributeEvent(managerTestSetup.apartment1HallwayId, Asset.NOTES, Integer.toString(counter++)))
        assetProcessingService.sendAttributeEvent(new AttributeEvent(managerTestSetup.apartment1Bedroom1Id, Asset.NOTES, Integer.toString(counter++)))
        assetProcessingService.sendAttributeEvent(new AttributeEvent(managerTestSetup.apartment1KitchenId, Asset.NOTES, Integer.toString(counter++)))
        assetProcessingService.sendAttributeEvent(new AttributeEvent(managerTestSetup.apartment1LivingroomId, Asset.NOTES, Integer.toString(counter++)))

        then: "5 messages should have been received"
        conditions.eventually {
            assert received.size() == 5
            assert received.any {it.topic == subscriptions[0] && ValueUtil.parse(it.payload, AttributeEvent.class).get().value.orElse(null) == "0"}
            assert received.any {it.topic == subscriptions[1] && ValueUtil.parse(it.payload, AttributeEvent.class).get().value.orElse(null) == "1"}
            assert received.any {it.topic == subscriptions[2] && ValueUtil.parse(it.payload, AttributeEvent.class).get().value.orElse(null) == "2"}
            assert received.any {it.topic == subscriptions[3] && ValueUtil.parse(it.payload, AttributeEvent.class).get().value.orElse(null) == "3"}
            assert received.any {it.topic == subscriptions[4] && ValueUtil.parse(it.payload, AttributeEvent.class).get().value.orElse(null) == "4"}
        }

        cleanup: "remove the client"
        if (client != null) {
            client.disconnect()
        }
    }

    @SuppressWarnings("GroovyAccessibility")
    def "Check MQTT client protocol and linked attribute deployment"() {

        given: "expected conditions"
        def conditions = new PollingConditions(timeout: 10, delay: 0.2)

        and: "the container starts"

        def assetStorageService = container.getService(AssetStorageService.class)
        def assetProcessingService = container.getService(AssetProcessingService.class)
        def agentService = container.getService(AgentService.class)
        def brokerService = container.getService(MQTTBrokerService.class)
        def defaultMQTTHandler = brokerService.getCustomHandlers().find{it instanceof DefaultMQTTHandler} as DefaultMQTTHandler
        def managerTestSetup = container.getService(SetupService.class).getTaskOfType(ManagerTestSetup.class)
        def keycloakTestSetup = container.getService(SetupService.class).getTaskOfType(KeycloakTestSetup.class)
        def mqttHost = brokerService.host
        def mqttPort = brokerService.port

        when: "an MQTT client agent is created to connect to this tests manager"
        def clientId = UniqueIdentifierGenerator.generateId()
        def agent = new MQTTAgent("Test agent")
            .setRealm(Constants.MASTER_REALM)
            .setClientId(clientId)
            .setHost(mqttHost)
            .setPort(mqttPort)
            .setUsernamePassword(new UsernamePassword(keycloakTestSetup.realmBuilding.name + ":" + keycloakTestSetup.serviceUser.username, keycloakTestSetup.serviceUser.secret))

        and: "the agent is added to the asset service"
        agent = assetStorageService.merge(agent)

        then: "the protocol should authenticate and the agent status should become CONNECTED"
        conditions.eventually {
            agent = assetStorageService.find(agent.id, Agent.class)
            assert agent.getAgentStatus().orElse(null) == ConnectionStatus.CONNECTED
        }

        when: "an asset is created with attributes linked to the agent"
        def asset = new ThingAsset("Test Asset")
            .setParent(agent)
            .addOrReplaceAttributes(
                // write attribute value
                new Attribute<>("readWriteTargetTemp", NUMBER)
                    .addMeta(
                        new MetaItem<>(AGENT_LINK, new MQTTAgentLink(agent.id)
                            .setSubscriptionTopic("${keycloakTestSetup.realmBuilding.name}/$clientId/${DefaultMQTTHandler.ATTRIBUTE_VALUE_TOPIC}/targetTemperature/${managerTestSetup.apartment1LivingroomId}")
                            .setPublishTopic("${keycloakTestSetup.realmBuilding.name}/$clientId/${DefaultMQTTHandler.ATTRIBUTE_VALUE_WRITE_TOPIC}/targetTemperature/${managerTestSetup.apartment1LivingroomId}")
                            .setWriteValue("%VALUE%")
                    ))
        )

        and: "the asset is merged into the asset service"
        asset = assetStorageService.merge(asset)

        then: "the linked attributes should be correctly linked"
        conditions.eventually {
            assert !(agentService.getProtocolInstance(agent.id) as MQTTProtocol).protocolMessageConsumers.isEmpty()
            assert ((agentService.getProtocolInstance(agent.id) as MQTTProtocol).client as MQTT_IOClient).topicConsumerMap.size() == 1
            assert ((agentService.getProtocolInstance(agent.id) as MQTTProtocol).client as MQTT_IOClient).topicConsumerMap.get("${keycloakTestSetup.realmBuilding.name}/$clientId/${DefaultMQTTHandler.ATTRIBUTE_VALUE_TOPIC}/targetTemperature/${managerTestSetup.apartment1LivingroomId}".toString()) != null
            def connection = brokerService.getConnectionFromClientID(clientId)
            assert connection != null
            assert defaultMQTTHandler.sessionSubscriptionConsumers.containsKey(getConnectionIDString(connection))
        }

        when: "the attribute referenced in the agent link is updated"
        ((SimulatorProtocol)agentService.getProtocolInstance(managerTestSetup.apartment1ServiceAgentId)).updateSensor(new AttributeEvent(managerTestSetup.apartment1LivingroomId, "targetTemperature", 99d))

        then: "the values should be stored in the database"
        conditions.eventually {
            def livingRoom = assetStorageService.find(managerTestSetup.apartment1LivingroomId)
            assert livingRoom != null
            assert livingRoom.getAttribute("targetTemperature").flatMap{it.value}.orElse(0d) == 99d
        }

        then: "the agent linked attribute should also have the updated value of the subscribed attribute"
        conditions.eventually {
            asset = assetStorageService.find(asset.getId(), true)
            assert asset.getAttribute("readWriteTargetTemp").get().getValue().orElse(null) == 99d
        }

        when: "the agent linked attribute is updated"
        def attributeEvent = new AttributeEvent(asset.id,
            "readWriteTargetTemp",
            19.5)
        assetProcessingService.sendAttributeEvent(attributeEvent)

        then: "the linked targetTemperature attribute should contain this written value (it should have been written to the target temp attribute and then read back again)"
        conditions.eventually {
            asset = assetStorageService.find(asset.getId(), true)
            assert asset.getAttribute("readWriteTargetTemp").flatMap{it.getValue()}.orElse(null) == 19.5d
        }

        when: "the agent link is updated to use a different qos for the subscription"
        asset.getAttribute("readWriteTargetTemp").get().addOrReplaceMeta(
                                        new MetaItem<>(AGENT_LINK, new MQTTAgentLink(agent.id)
                                                .setSubscriptionTopic("${keycloakTestSetup.realmBuilding.name}/$clientId/${DefaultMQTTHandler.ATTRIBUTE_VALUE_TOPIC}/targetTemperature/${managerTestSetup.apartment1LivingroomId}")
                                                .setPublishTopic("${keycloakTestSetup.realmBuilding.name}/$clientId/${DefaultMQTTHandler.ATTRIBUTE_VALUE_WRITE_TOPIC}/targetTemperature/${managerTestSetup.apartment1LivingroomId}")
                                                .setQos(2)
                                                .setWriteValue("%VALUE%")
                                        ))

        and: "the asset is merged into the asset service"
        asset = assetStorageService.merge(asset)

        then: "the linked attributes should be correctly linked"
        conditions.eventually {
            assert !(agentService.getProtocolInstance(agent.id) as MQTTProtocol).protocolMessageConsumers.isEmpty()
            assert ((agentService.getProtocolInstance(agent.id) as MQTTProtocol).client as MQTT_IOClient).topicConsumerMap.size() == 1
            assert ((agentService.getProtocolInstance(agent.id) as MQTTProtocol).client as MQTT_IOClient).topicConsumerMap.get("${keycloakTestSetup.realmBuilding.name}/$clientId/${DefaultMQTTHandler.ATTRIBUTE_VALUE_TOPIC}/targetTemperature/${managerTestSetup.apartment1LivingroomId}".toString()) != null
            def connection = brokerService.getConnectionFromClientID(clientId)
            assert connection != null
            assert defaultMQTTHandler.sessionSubscriptionConsumers.containsKey(getConnectionIDString(connection))
        }
    }

    def "Check MQTT client protocol message match filter support"() {

        given: "expected conditions"
        def conditions = new PollingConditions(timeout: 10, delay: 0.2)

        when: "the container starts"
        def assetStorageService = container.getService(AssetStorageService.class)
        def assetProcessingService = container.getService(AssetProcessingService.class)
        def brokerService = container.getService(MQTTBrokerService.class)
        def keycloakTestSetup = container.getService(SetupService.class).getTaskOfType(KeycloakTestSetup.class)
        def mqttHost = brokerService.host
        def mqttPort = brokerService.port
        def mqttAgentClientId = UniqueIdentifierGenerator.generateId()

        and: "a test asset is created"
        def testThing = new ThingAsset("Test thing")
            .setRealm(keycloakTestSetup.realmBuilding.name)
            .addOrReplaceAttributes(
                new Attribute<Object>("test", JSON)
            )

        and: "the test asset is added to the asset service"
        testThing = assetStorageService.merge(testThing)

        and: "a MQTT agent is created"
        def agent = new MQTTAgent("Test agent")
            .setRealm(MASTER_REALM)
            .setClientId(mqttAgentClientId)
            .setHost(mqttHost)
            .setPort(mqttPort)
            .setUsernamePassword(new UsernamePassword(keycloakTestSetup.realmBuilding.name + ":" + keycloakTestSetup.serviceUser.username, keycloakTestSetup.serviceUser.secret))

        and: "the agent is added to the asset service"
        agent = assetStorageService.merge(agent)

        then: "the protocol should authenticate and the agent status should become CONNECTED"
        conditions.eventually {
            agent = assetStorageService.find(agent.id, Agent.class)
            assert agent.getAgentStatus().orElse(null) == ConnectionStatus.CONNECTED
        }

        when: "an asset is created with attributes linked to the agent"
        def subscriptionTopic = "${keycloakTestSetup.realmBuilding.name}/$mqttAgentClientId/${DefaultMQTTHandler.ATTRIBUTE_VALUE_TOPIC}/test/${testThing.id}"
        def asset = new ThingAsset("Test Asset")
            .setParent(agent)
            .addOrReplaceAttributes(
                new Attribute<>("temperature", NUMBER)
                    .addMeta(
                        new MetaItem<>(AGENT_LINK, new MQTTAgentLink(agent.id)
                            .setSubscriptionTopic(subscriptionTopic)
                            .setValueFilters([new JsonPathFilter('$.temperature', true, false)] as ValueFilter[])
                            .setMessageMatchFilters([new JsonPathFilter('$.port', true, false)] as ValueFilter[])
                            .setMessageMatchPredicate(new NumberPredicate(85))
                        )),
                new Attribute<>("switchStatus", BOOLEAN)
                    .addMeta(
                        new MetaItem<>(AGENT_LINK, new MQTTAgentLink(agent.id)
                            .setSubscriptionTopic(subscriptionTopic)
                            .setValueFilters([new JsonPathFilter('$.switch_status', true, false)] as ValueFilter[])
                            .setMessageMatchFilters([new JsonPathFilter('$.port', true, false)] as ValueFilter[])
                            .setMessageMatchPredicate(new NumberPredicate(85))
                            .setValueConverter([
                                SWITCH_ON : true,
                                SWITCH_OFF: false
                            ])
                        ))
            )

        and: "the asset is merged into the asset service"
        asset = assetStorageService.merge(asset)

        and: "a message which should pass the message match filter is published"
        def json = [
            port: 85,
            temperature: 19.5,
            switch_status: "switch_on"
        ]
        def attributeEvent = new AttributeEvent(testThing.id, "test", json)
        Thread.sleep(200)
        assetProcessingService.sendAttributeEvent(attributeEvent)

        then: "asset attribute values should be updated"
        conditions.eventually {
            asset = assetStorageService.find(asset.id, true)
            assert asset.getAttribute("temperature").flatMap { it.value }.map { it == 19.5 }.orElse(false)
            assert asset.getAttribute("switchStatus").flatMap { it.value }.map { it == true }.orElse(false)
        }

        when: "a message which should not pass the message match filter is published"
        json = [
            port: 1,
            temperature: 20.5,
            switch_status: "switch_off"
        ]
        attributeEvent = new AttributeEvent(testThing.id, "test", json)
        assetProcessingService.sendAttributeEvent(attributeEvent)

        then: "asset attribute values should not be updated"
        def start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < 2000) {
            asset = assetStorageService.find(asset.id, true)
            assert asset.getAttribute("temperature").flatMap { it.value }.map { it == 19.5 }.orElse(false)
            assert asset.getAttribute("switchStatus").flatMap { it.value }.map { it == true }.orElse(false)
            Thread.sleep((100))
        }

        when: "a message which should pass the message match filter is published"
        json = [
            port: 85,
            temperature: 21.5,
            switch_status: "switch_off"
        ]
        attributeEvent = new AttributeEvent(testThing.id, "test", json)
        assetProcessingService.sendAttributeEvent(attributeEvent)

        then: "asset attribute values should be updated"
        conditions.eventually {
            asset = assetStorageService.find(asset.id, true)
            assert asset.getAttribute("temperature").flatMap { it.value }.map { it == 21.5 }.orElse(false)
            assert asset.getAttribute("switchStatus").flatMap { it.value }.map { it == false }.orElse(false)
        }
    }

    def "Check MQTT client protocol wildcard subscriptions"() {

        given: "expected conditions"
        def conditions = new PollingConditions(timeout: 10, delay: 0.2)

        when: "the container starts"
        def assetStorageService = container.getService(AssetStorageService.class)
        def assetProcessingService = container.getService(AssetProcessingService.class)
        def agentService = container.getService(AgentService.class)
        def brokerService = container.getService(MQTTBrokerService.class)
        def keycloakTestSetup = container.getService(SetupService.class).getTaskOfType(KeycloakTestSetup.class)
        def mqttHost = brokerService.host
        def mqttPort = brokerService.port
        def mqttAgentClientId = UniqueIdentifierGenerator.generateId()

        and: "test assets are created"
        def testThing1 = new ThingAsset("Test thing 1")
            .setRealm(keycloakTestSetup.realmBuilding.name)
            .addOrReplaceAttributes(
                new Attribute<Object>("temperature", NUMBER)
            )
            .addOrReplaceAttributes(
                new Attribute<Object>("humidity", NUMBER)
            )
        def testThing2 = new ThingAsset("Test thing 2")
            .setRealm(keycloakTestSetup.realmBuilding.name)
            .addOrReplaceAttributes(
                new Attribute<Object>("pressure", NUMBER)
            )
        def testThing3 = new ThingAsset("Test thing 3")
            .setRealm(keycloakTestSetup.realmBuilding.name)
            .addOrReplaceAttributes(
                new Attribute<Object>("uvIndex", NUMBER)
            )

        and: "the test assets are added to the asset service"
        testThing1 = assetStorageService.merge(testThing1)
        testThing2 = assetStorageService.merge(testThing2)
        testThing3 = assetStorageService.merge(testThing3)

        and: "a MQTT agent is created"
        def wildcardTopic1 = "${keycloakTestSetup.realmBuilding.name}/$mqttAgentClientId/${DefaultMQTTHandler.ATTRIBUTE_VALUE_TOPIC}/+/${testThing1.id}".toString()
        def wildcardTopic2 = "${keycloakTestSetup.realmBuilding.name}/$mqttAgentClientId/${DefaultMQTTHandler.ATTRIBUTE_VALUE_TOPIC}/+/${testThing2.id}".toString()
        def agent = new MQTTAgent("Test agent")
            .setRealm(MASTER_REALM)
            .setClientId(mqttAgentClientId)
            .setHost(mqttHost)
            .setPort(mqttPort)
            .setUsernamePassword(new UsernamePassword(keycloakTestSetup.realmBuilding.name + ":" + keycloakTestSetup.serviceUser.username, keycloakTestSetup.serviceUser.secret))
            .setWildcardSubscriptionTopics([wildcardTopic1, wildcardTopic2] as String[])

        and: "the agent is added to the asset service"
        agent = assetStorageService.merge(agent)

        then: "the protocol should authenticate and the agent status should become CONNECTED"
        conditions.eventually {
            agent = assetStorageService.find(agent.id, Agent.class)
            assert agent.getAgentStatus().orElse(null) == ConnectionStatus.CONNECTED
        }

        and: "there should be wildcard topics"
        conditions.eventually {
            def protocol = (MQTTProtocol)agentService.getProtocolInstance(agent.id)
            assert protocol.wildcardTopics.size() == 2
            assert protocol.wildcardTopics.any {it.toString() == wildcardTopic1}
            assert protocol.wildcardTopics.any {it.toString() == wildcardTopic2}
        }

        when: "an asset is created with attributes linked to the agent"
        def temperatureTopic = "${keycloakTestSetup.realmBuilding.name}/$mqttAgentClientId/${DefaultMQTTHandler.ATTRIBUTE_VALUE_TOPIC}/temperature/${testThing1.id}".toString()
        def humidityTopic = "${keycloakTestSetup.realmBuilding.name}/$mqttAgentClientId/${DefaultMQTTHandler.ATTRIBUTE_VALUE_TOPIC}/humidity/${testThing1.id}".toString()
        def pressureTopic = "${keycloakTestSetup.realmBuilding.name}/$mqttAgentClientId/${DefaultMQTTHandler.ATTRIBUTE_VALUE_TOPIC}/pressure/${testThing2.id}".toString()
        def uvIndexTopic = "${keycloakTestSetup.realmBuilding.name}/$mqttAgentClientId/${DefaultMQTTHandler.ATTRIBUTE_VALUE_TOPIC}/uvIndex/${testThing3.id}".toString()
        def asset = new ThingAsset("Test Asset")
            .setParent(agent)
            .addOrReplaceAttributes(
                new Attribute<>("temperature", NUMBER)
                    .addMeta(
                        new MetaItem<>(AGENT_LINK, new MQTTAgentLink(agent.id)
                            .setSubscriptionTopic(temperatureTopic)
                        )),
                new Attribute<>("humidity", NUMBER)
                    .addMeta(
                        new MetaItem<>(AGENT_LINK, new MQTTAgentLink(agent.id)
                            .setSubscriptionTopic(humidityTopic)
                        )),
                new Attribute<>("humidity2", NUMBER)
                    .addMeta(
                        new MetaItem<>(AGENT_LINK, new MQTTAgentLink(agent.id)
                            .setSubscriptionTopic(humidityTopic)
                        )),
                new Attribute<>("pressure", NUMBER)
                    .addMeta(
                        new MetaItem<>(AGENT_LINK, new MQTTAgentLink(agent.id)
                            .setSubscriptionTopic(pressureTopic)
                        )),
                new Attribute<>("uvIndex", NUMBER)
                    .addMeta(
                        new MetaItem<>(AGENT_LINK, new MQTTAgentLink(agent.id)
                            .setSubscriptionTopic(uvIndexTopic)
                        ))
            )

        and: "the asset is merged into the asset service"
        asset = assetStorageService.merge(asset)

        then: "there should be wildcard subscriptions and regular subscriptions"
        conditions.eventually {
            def protocol = (MQTTProtocol)agentService.getProtocolInstance(agent.id)
            def client = protocol.@client
            assert client.topicConsumerMap.containsKey(wildcardTopic1)
            assert client.topicConsumerMap.containsKey(wildcardTopic2)
            assert client.topicConsumerMap.containsKey(uvIndexTopic)
            assert protocol.wildcardTopicConsumerMap.size() == 2
            assert protocol.wildcardTopicConsumerMap.get(wildcardTopic1).size() == 2
            assert protocol.wildcardTopicConsumerMap.get(wildcardTopic1).containsKey(temperatureTopic)
            assert protocol.wildcardTopicConsumerMap.get(wildcardTopic1).get(temperatureTopic).size() == 1
            assert protocol.wildcardTopicConsumerMap.get(wildcardTopic1).containsKey(humidityTopic)
            assert protocol.wildcardTopicConsumerMap.get(wildcardTopic1).get(humidityTopic).size() == 2
            assert protocol.wildcardTopicConsumerMap.get(wildcardTopic2).size() == 1
            assert protocol.wildcardTopicConsumerMap.get(wildcardTopic2).containsKey(pressureTopic)
            assert protocol.wildcardTopicConsumerMap.get(wildcardTopic2).get(pressureTopic).size() == 1
        }

        when: "mqtt data is published"
        def attributeEvent = new AttributeEvent(testThing1.id, "temperature", 19.5)
        assetProcessingService.sendAttributeEvent(attributeEvent)
        attributeEvent = new AttributeEvent(testThing1.id, "humidity", 85.0)
        assetProcessingService.sendAttributeEvent(attributeEvent)

        attributeEvent = new AttributeEvent(testThing2.id, "pressure", 1013.25)
        assetProcessingService.sendAttributeEvent(attributeEvent)

        attributeEvent = new AttributeEvent(testThing3.id, "uvIndex", 3.0)
        assetProcessingService.sendAttributeEvent(attributeEvent)

        then: "asset attribute values should be updated"
        conditions.eventually {
            asset = assetStorageService.find(asset.id, true)
            assert asset.getAttribute("temperature").flatMap { it.value }.map { it == 19.5 }.orElse(false)
            assert asset.getAttribute("humidity").flatMap { it.value }.map { it == 85 }.orElse(false)
            assert asset.getAttribute("humidity2").flatMap { it.value }.map { it == 85 }.orElse(false)
            assert asset.getAttribute("pressure").flatMap { it.value }.map { it == 1013.25 }.orElse(false)
            assert asset.getAttribute("uvIndex").flatMap { it.value }.map { it == 3.0 }.orElse(false)
        }

        when: "an attribute is removed that uses the wildcard subscription"
        asset.attributes.remove("humidity2")
        asset = assetStorageService.merge(asset)

        then: "the wildcard subscription should still exist as another attribute still matches it"
        conditions.eventually {
            def protocol = (MQTTProtocol)agentService.getProtocolInstance(agent.id)
            def client = protocol.@client
            assert client.topicConsumerMap.containsKey(wildcardTopic1)
            assert client.topicConsumerMap.containsKey(wildcardTopic2)
            assert client.topicConsumerMap.containsKey(uvIndexTopic)
            assert protocol.wildcardTopicConsumerMap.size() == 2
            assert protocol.wildcardTopicConsumerMap.get(wildcardTopic1).size() == 2
            assert protocol.wildcardTopicConsumerMap.get(wildcardTopic1).containsKey(temperatureTopic)
            assert protocol.wildcardTopicConsumerMap.get(wildcardTopic1).get(temperatureTopic).size() == 1
            assert protocol.wildcardTopicConsumerMap.get(wildcardTopic1).containsKey(humidityTopic)
            assert protocol.wildcardTopicConsumerMap.get(wildcardTopic1).get(humidityTopic).size() == 1
            assert protocol.wildcardTopicConsumerMap.get(wildcardTopic2).size() == 1
            assert protocol.wildcardTopicConsumerMap.get(wildcardTopic2).containsKey(pressureTopic)
            assert protocol.wildcardTopicConsumerMap.get(wildcardTopic2).get(pressureTopic).size() == 1
        }

        when: "mqtt data is published"
        attributeEvent = new AttributeEvent(testThing1.id, "temperature", 20.5)
        assetProcessingService.sendAttributeEvent(attributeEvent)
        attributeEvent = new AttributeEvent(testThing1.id, "humidity", 90.0)
        assetProcessingService.sendAttributeEvent(attributeEvent)

        attributeEvent = new AttributeEvent(testThing2.id, "pressure", 1000)
        assetProcessingService.sendAttributeEvent(attributeEvent)

        attributeEvent = new AttributeEvent(testThing3.id, "uvIndex", 4.0)
        assetProcessingService.sendAttributeEvent(attributeEvent)

        then: "asset attribute values should be updated"
        conditions.eventually {
            asset = assetStorageService.find(asset.id, true)
            assert asset.getAttribute("temperature").flatMap { it.value }.map { it == 20.5 }.orElse(false)
            assert asset.getAttribute("humidity").flatMap { it.value }.map { it == 90 }.orElse(false)
            assert asset.getAttribute("pressure").flatMap { it.value }.map { it == 1000 }.orElse(false)
            assert asset.getAttribute("uvIndex").flatMap { it.value }.map { it == 4.0 }.orElse(false)
        }
    }

    /**
    * This test is not fully done right now, as a non-internet-requiring test would require mTLS functionality within the
    * OpenRemote broker, something that will eventually be done.
    *
    * For now, we will use test.mosquitto.org, an open broker that is provided by the creators of mosquitto. They provide
    * multiple ways of accessing the broker, including one that requires a client certificate and TLS. Using that broker,
    * we will test the mTLS and TLS functionalities.
    *
    * To access the broker, we need them to sign our certificate. To do that, we can request
    *
    * If the test cannot reach the host, then the test is passed.
    * */
    @Ignore
    @SuppressWarnings("GroovyAccessibility")
    def "Check MQTT client protocol mTLS support"() {

        given: "expected conditions"
        def conditions = new PollingConditions(timeout: 100, delay: 0.2)

        when:
        Socket webSocket = new Socket("test.mosquitto.org", 443)
        Socket mqttSocket = new Socket("test.mosquitto.org", 8884)
        HttpClient testClient = HttpClient.newHttpClient()
        HttpRequest testRequest = HttpRequest.newBuilder()
                .GET()
                .uri(new URI("https://test.mosquitto.org/ssl/index.php"))
                .build()

        HttpResponse<String> testResponse = testClient.send(testRequest, HttpResponse.BodyHandlers.ofString())

        then:
        if(webSocket == null) throw new TestAbortedException("Mosquitto web server unavailable");
        if(mqttSocket == null) throw new TestAbortedException("Mosquitto MQTT broker unavailable");
        if(testResponse.statusCode() != 200) throw new TestAbortedException("Mosquitto signing service unavailable");

        and: "the container starts"
        def container = startContainer(defaultConfig(), defaultServices())
        def assetStorageService = container.getService(AssetStorageService.class)
        def keyStoreService = container.getService(KeyStoreServiceImpl.class)
        def mqttHost = "test.mosquitto.org"
        def mqttPort = 8884
        def keystorePassword = "secret"
        def keyPassword = "secret"
        def aliasName = "testalias"
        def keyAlias = Constants.MASTER_REALM + "." + aliasName

        when:
        KeyStore clientKeystore = keyStoreService.getKeyStore()
        KeyStore clientTruststore = keyStoreService.getTrustStore()

        // Create keystore and keypair
        clientKeystore = createKeystore(clientKeystore, keyAlias, keyPassword)

        // Get SSL certificate of test.mosquitto.org:8884
        def cert = getCertificate("https://test.mosquitto.org/ssl/mosquitto.org.crt")

        // Import X509 Certificate into the truststore
        clientTruststore.setCertificateEntry(keyAlias, cert)

        // Generate the CSR, to be sent to the Mosquitto server for signing, URLEncode it
        String csrPem = generateCSR(clientKeystore, keyAlias, keyPassword)
        csrPem = URLEncoder.encode(csrPem, StandardCharsets.UTF_8.toString())

        //Send the CSR to their webserver that automatically signs the certificate
        HttpClient client = HttpClient.newHttpClient()
        HttpRequest request = HttpRequest.newBuilder()
                .uri(new URI("https://test.mosquitto.org/ssl/index.php"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("csr="+csrPem))
                .build()

        // Take the response,
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString())

        String signedCertPem = response.body()

        if (response.statusCode() != 200) throw new Exception("Signing request failed")

        clientKeystore = importCertificate(clientKeystore, keyAlias, signedCertPem, keystorePassword.toCharArray())

        keyStoreService.storeKeyStore(clientKeystore)
        keyStoreService.storeTrustStore(clientTruststore)

        and: "an MQTT client agent is created to connect to this tests manager"
        def clientId = UniqueIdentifierGenerator.generateId()
        def agent = new MQTTAgent("Test agent")
                .setRealm(Constants.MASTER_REALM)
                .setClientId(clientId)
                .setPort(mqttPort)
                .setHost(mqttHost)
                .setSecureMode(true)
                .setCertificateAlias(aliasName)
//                .setUsernamePassword(new UsernamePassword(keycloakTestSetup.realmBuilding.name + ":" + keycloakTestSetup.serviceUser.username, keycloakTestSetup.serviceUser.secret))
        and: "the agent is added to the asset service"
        agent = assetStorageService.merge(agent)

        then: "the protocol should authenticate and the agent status should become CONNECTED"
        conditions.eventually {
            agent = assetStorageService.find(agent.id, Agent.class)
            assert agent.getAgentStatus().orElse(null) == ConnectionStatus.CONNECTED
        }

        when: "the agent is connected, show the SSL certificates used"
        def protocol = agent.getProtocolInstance() as MQTTProtocol
        then: "test"
        protocol.properties
    }


    KeyStore createKeystore(KeyStore keyStore, String alias, String keyPassword) {
        // Create a new keypair
        KeyPairGenerator keyGen = KeyPairGenerator.getInstance("RSA")
        keyGen.initialize(2048)
        def keyPair = keyGen.generateKeyPair()

        /// Generate a self-signed certificate
        X500Name subject = new X500Name("CN=mTLS Test,OU=OpenRemote,O=OpenRemote,L=Eindhoven,ST=Noord-Brabant,C=NL")
        long now = System.currentTimeMillis()
        Date startDate = new Date(now)
        Date endDate = new Date(now + 365 * 86400000L)
        X509v3CertificateBuilder certBuilder = new JcaX509v3CertificateBuilder(
                subject,
                BigInteger.valueOf(now),
                startDate,
                endDate,
                subject,
                keyPair.getPublic()
        )


        ContentSigner contentSigner = new JcaContentSignerBuilder("SHA256WithRSA").build(keyPair.getPrivate())
        X509Certificate cert = new JcaX509CertificateConverter().getCertificate(certBuilder.build(contentSigner))

        keyStore.setKeyEntry(alias, keyPair.getPrivate(), keyPassword.toCharArray(), new X509Certificate[] { cert })

        // Store the keystore
        return keyStore
    }

    X509Certificate getCertificate(String url) {
        URL pemUrl = new URL(url)
        CertificateFactory cf = CertificateFactory.getInstance("X.509")
        X509Certificate cert = (X509Certificate) cf.generateCertificate(pemUrl.openStream())

        return cert
    }

    String generateCSR(KeyStore keyStore, String alias, String keyPassword) {

        // Retrieve the Private Key and Certificate
        PrivateKey privateKey = (PrivateKey) keyStore.getKey(alias, keyPassword.toCharArray())
        Certificate cert = keyStore.getCertificate(alias)
        X509Certificate x509Cert = (X509Certificate) cert

        // Build X500Name from the Certificate's Subject
        X500Name x500Name = new X500Name(x509Cert.getSubjectX500Principal().getName())

        // Build ContentSigner
        ContentSigner signer = new JcaContentSignerBuilder("SHA256withRSA").build(privateKey)

        // Build PKCS10CertificationRequest
        JcaPKCS10CertificationRequestBuilder csrBuilder = new JcaPKCS10CertificationRequestBuilder(x500Name, x509Cert.getPublicKey())
        PKCS10CertificationRequest csr = csrBuilder.build(signer)

        String csrPEM
        try (StringWriter stringWriter = new StringWriter()
             PemWriter pemWriter = new PemWriter(stringWriter)) {
            pemWriter.writeObject(new PemObject("CERTIFICATE REQUEST", csr.getEncoded()))
            pemWriter.flush()
            csrPEM = stringWriter.toString()

        }
        return csrPEM
    }

    KeyStore importCertificate(KeyStore keyStore, String alias, String signedCertPem, char[] keystorePassword) {
        try {
            // Convert PEM to X509Certificate
            byte[] certBytes = Base64.decoder.decode(signedCertPem
                    .replace("-----BEGIN CERTIFICATE-----", "")
                    .replace("-----END CERTIFICATE-----", "")
                    .replaceAll("\\s", ""))
            CertificateFactory certFactory = CertificateFactory.getInstance("X.509")
            X509Certificate signedCert = certFactory.generateCertificate(new ByteArrayInputStream(certBytes))

            // Set the certificate chain
            Certificate[] certChain = [signedCert] as Certificate[]
            keyStore.setKeyEntry(alias, keyStore.getKey(alias, keystorePassword), keystorePassword, certChain)

            return keyStore
        } catch (Exception e) {
            e.printStackTrace()
        }
    }
}
