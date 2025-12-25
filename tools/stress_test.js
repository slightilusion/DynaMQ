/**
 * DynaMQ MQTT Stress Test Script
 * Simulates 3000+ concurrent MQTT clients
 * 
 * Usage: npm install mqtt && node stress_test.js
 */

const mqtt = require('mqtt');

// Configuration
const CONFIG = {
    host: process.env.MQTT_HOST || 'mq.dynabot.org',
    port: parseInt(process.env.MQTT_PORT || '1880'),
    numClients: parseInt(process.env.NUM_CLIENTS || '5000'),
    messagesPerClient: parseInt(process.env.MESSAGES || '30'),
    messageInterval: parseInt(process.env.INTERVAL || '200'), // ms
    topicPrefix: process.env.TOPIC_PREFIX || 'sys/test',
    batchSize: parseInt(process.env.BATCH_SIZE || '200'),
    batchDelay: parseInt(process.env.BATCH_DELAY || '300'), // ms
    continuous: process.env.CONTINUOUS === 'true' || process.env.CONTINUOUS === '1', // 持续模式
    duration: parseInt(process.env.DURATION || '0'), // 持续时间(秒), 0=永久
};

// Statistics
const stats = {
    connected: 0,
    connectFailed: 0,
    messagesSent: 0,
    messagesReceived: 0,
    publishFailed: 0,
    disconnected: 0,
    startTime: Date.now(),
};

const clients = [];

function randomString(length) {
    const chars = 'abcdefghijklmnopqrstuvwxyz0123456789';
    let result = '';
    for (let i = 0; i < length; i++) {
        result += chars.charAt(Math.floor(Math.random() * chars.length));
    }
    return result;
}

function sleep(ms) {
    return new Promise(resolve => setTimeout(resolve, ms));
}

async function createClient(index) {
    const clientId = `stress-${index.toString().padStart(5, '0')}-${randomString(4)}`;
    const url = `mqtt://${CONFIG.host}:${CONFIG.port}`;

    return new Promise((resolve) => {
        const client = mqtt.connect(url, {
            clientId,
            clean: true,
            connectTimeout: 10000,
            keepalive: 60,
            reconnectPeriod: 0, // Don't auto-reconnect in stress test
        });

        let connected = false;
        let messagesSent = 0;

        client.on('connect', async () => {
            connected = true;
            stats.connected++;

            // Subscribe
            client.subscribe(`${CONFIG.topicPrefix}/response/${clientId}`, { qos: 1 });

            // Send messages
            const sendMessages = async () => {
                let seq = 0;
                while (connected) {
                    // 检查是否达到持续时间限制
                    if (CONFIG.duration > 0) {
                        const elapsed = (Date.now() - stats.startTime) / 1000;
                        if (elapsed >= CONFIG.duration) {
                            break;
                        }
                    }

                    // 非持续模式下，检查消息数量限制
                    if (!CONFIG.continuous && seq >= CONFIG.messagesPerClient) {
                        break;
                    }

                    const topic = `${CONFIG.topicPrefix}/up`;
                    const payload = JSON.stringify({
                        client: clientId,
                        seq: seq++,
                        ts: Date.now(),
                    });

                    client.publish(topic, payload, { qos: 1 }, (err) => {
                        if (err) {
                            stats.publishFailed++;
                        } else {
                            stats.messagesSent++;
                        }
                    });

                    await sleep(CONFIG.messageInterval);
                }

                // 只有非持续模式才自动断开
                if (!CONFIG.continuous) {
                    setTimeout(() => {
                        client.end();
                    }, 2000);
                }
            };

            sendMessages();
        });

        client.on('message', (topic, message) => {
            stats.messagesReceived++;
        });

        client.on('error', (err) => {
            if (!connected) {
                stats.connectFailed++;
            }
        });

        client.on('close', () => {
            if (connected) {
                stats.disconnected++;
            }
            resolve();
        });

        clients.push(client);
    });
}

function printStats() {
    const elapsed = (Date.now() - stats.startTime) / 1000;
    console.log(`\n[${elapsed.toFixed(1)}s] Stats:`);
    console.log(`  Connected: ${stats.connected}/${CONFIG.numClients}, Failed: ${stats.connectFailed}`);
    console.log(`  Messages Sent: ${stats.messagesSent}, Received: ${stats.messagesReceived}`);
    console.log(`  Publish Failed: ${stats.publishFailed}, Disconnected: ${stats.disconnected}`);
    console.log(`  Throughput: ${(stats.messagesSent / elapsed).toFixed(1)} msg/s`);
}

async function runStressTest() {
    console.log(`
╔══════════════════════════════════════════════════════════════╗
║           DynaMQ MQTT Stress Test                            ║
╠══════════════════════════════════════════════════════════════╣
║  Host: ${CONFIG.host}:${CONFIG.port}
║  Clients: ${CONFIG.numClients}
║  Messages per client: ${CONFIG.messagesPerClient}
║  Message interval: ${CONFIG.messageInterval}ms
║  Topic prefix: ${CONFIG.topicPrefix}
╚══════════════════════════════════════════════════════════════╝
`);

    // Print stats periodically
    const statsInterval = setInterval(printStats, 3000);

    // Create clients in batches
    const promises = [];

    console.log(`Creating ${CONFIG.numClients} clients in batches of ${CONFIG.batchSize}...`);

    for (let i = 0; i < CONFIG.numClients; i += CONFIG.batchSize) {
        const batchEnd = Math.min(i + CONFIG.batchSize, CONFIG.numClients);
        console.log(`  Creating batch ${Math.floor(i / CONFIG.batchSize) + 1}: clients ${i}-${batchEnd - 1}`);

        for (let j = i; j < batchEnd; j++) {
            promises.push(createClient(j));
        }

        await sleep(CONFIG.batchDelay);
    }

    console.log(`\nWaiting for all ${promises.length} clients to complete...`);

    await Promise.all(promises);

    clearInterval(statsInterval);

    const elapsed = (Date.now() - stats.startTime) / 1000;

    console.log(`
╔══════════════════════════════════════════════════════════════╗
║           Final Results                                      ║
╠══════════════════════════════════════════════════════════════╣
║  Total Time: ${elapsed.toFixed(2)}s
║  Connected: ${stats.connected} / ${CONFIG.numClients}
║  Connect Failed: ${stats.connectFailed}
║  Messages Sent: ${stats.messagesSent}
║  Messages Received: ${stats.messagesReceived}
║  Publish Failed: ${stats.publishFailed}
║  Avg Throughput: ${(stats.messagesSent / elapsed).toFixed(1)} msg/s
║  Connection Rate: ${(stats.connected / elapsed).toFixed(1)} conn/s
╚══════════════════════════════════════════════════════════════╝
`);

    process.exit(0);
}

// Handle Ctrl+C
process.on('SIGINT', () => {
    console.log('\nStopping stress test...');
    clients.forEach(client => client.end(true));
    printStats();
    process.exit(0);
});

runStressTest().catch(console.error);
