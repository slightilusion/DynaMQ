#!/usr/bin/env python3
"""
DynaMQ MQTT Stress Test Script
Simulates 3000 concurrent MQTT clients for performance testing.
"""

import asyncio
import time
import random
import string
import argparse
from collections import defaultdict

try:
    from paho.mqtt import client as mqtt
except ImportError:
    print("Please install paho-mqtt: pip install paho-mqtt")
    exit(1)

# Configuration
DEFAULT_HOST = "mq.dynabot.org"
DEFAULT_PORT = 1880
DEFAULT_CLIENTS = 3000
DEFAULT_MESSAGES_PER_CLIENT = 10
DEFAULT_MESSAGE_INTERVAL = 1.0
DEFAULT_TOPIC_PREFIX = "stress/test"

# Statistics
stats = {
    "connected": 0,
    "connect_failed": 0,
    "messages_sent": 0,
    "messages_received": 0,
    "publish_failed": 0,
    "disconnected": 0,
}
stats_lock = asyncio.Lock()

class MqttStressClient:
    def __init__(self, client_id, host, port, topic_prefix, messages, interval):
        self.client_id = client_id
        self.host = host
        self.port = port
        self.topic_prefix = topic_prefix
        self.messages_to_send = messages
        self.interval = interval
        self.client = None
        self.connected = False
        
    def on_connect(self, client, userdata, flags, rc):
        if rc == 0:
            self.connected = True
            asyncio.get_event_loop().call_soon_threadsafe(
                lambda: asyncio.create_task(self._update_stat("connected", 1))
            )
        else:
            asyncio.get_event_loop().call_soon_threadsafe(
                lambda: asyncio.create_task(self._update_stat("connect_failed", 1))
            )
    
    def on_disconnect(self, client, userdata, rc):
        self.connected = False
        asyncio.get_event_loop().call_soon_threadsafe(
            lambda: asyncio.create_task(self._update_stat("disconnected", 1))
        )
    
    def on_message(self, client, userdata, msg):
        asyncio.get_event_loop().call_soon_threadsafe(
            lambda: asyncio.create_task(self._update_stat("messages_received", 1))
        )
    
    async def _update_stat(self, key, value):
        async with stats_lock:
            stats[key] += value
    
    async def run(self):
        try:
            self.client = mqtt.Client(client_id=self.client_id, protocol=mqtt.MQTTv311)
            self.client.on_connect = self.on_connect
            self.client.on_disconnect = self.on_disconnect
            self.client.on_message = self.on_message
            
            # Connect
            self.client.connect_async(self.host, self.port, keepalive=60)
            self.client.loop_start()
            
            # Wait for connection
            for _ in range(50):  # 5 second timeout
                if self.connected:
                    break
                await asyncio.sleep(0.1)
            
            if not self.connected:
                async with stats_lock:
                    stats["connect_failed"] += 1
                return
            
            # Subscribe to response topic
            self.client.subscribe(f"{self.topic_prefix}/response/{self.client_id}", qos=1)
            
            # Send messages
            for i in range(self.messages_to_send):
                if not self.connected:
                    break
                    
                topic = f"{self.topic_prefix}/data/{self.client_id}"
                payload = f'{{"client":"{self.client_id}","seq":{i},"ts":{int(time.time()*1000)}}}'
                
                result = self.client.publish(topic, payload, qos=1)
                if result.rc == mqtt.MQTT_ERR_SUCCESS:
                    async with stats_lock:
                        stats["messages_sent"] += 1
                else:
                    async with stats_lock:
                        stats["publish_failed"] += 1
                
                await asyncio.sleep(self.interval)
            
            # Keep alive for a bit
            await asyncio.sleep(2)
            
        except Exception as e:
            print(f"Client {self.client_id} error: {e}")
        finally:
            if self.client:
                self.client.loop_stop()
                self.client.disconnect()

async def print_stats():
    """Print statistics periodically"""
    start_time = time.time()
    while True:
        await asyncio.sleep(2)
        elapsed = time.time() - start_time
        async with stats_lock:
            print(f"\n[{elapsed:.1f}s] Stats:")
            print(f"  Connected: {stats['connected']}, Failed: {stats['connect_failed']}")
            print(f"  Messages Sent: {stats['messages_sent']}, Received: {stats['messages_received']}")
            print(f"  Publish Failed: {stats['publish_failed']}, Disconnected: {stats['disconnected']}")
            if elapsed > 0:
                print(f"  Throughput: {stats['messages_sent']/elapsed:.1f} msg/s")

async def run_stress_test(host, port, num_clients, messages, interval, topic_prefix):
    print(f"""
╔══════════════════════════════════════════════════════════════╗
║           DynaMQ MQTT Stress Test                            ║
╠══════════════════════════════════════════════════════════════╣
║  Host: {host}:{port}
║  Clients: {num_clients}
║  Messages per client: {messages}
║  Message interval: {interval}s
║  Topic prefix: {topic_prefix}
╚══════════════════════════════════════════════════════════════╝
""")
    
    # Start stats printer
    stats_task = asyncio.create_task(print_stats())
    
    # Create clients in batches to avoid overwhelming the broker
    batch_size = 100
    clients = []
    
    print(f"Creating {num_clients} clients in batches of {batch_size}...")
    
    for batch_start in range(0, num_clients, batch_size):
        batch_end = min(batch_start + batch_size, num_clients)
        batch_clients = []
        
        for i in range(batch_start, batch_end):
            client_id = f"stress-{i:05d}-{''.join(random.choices(string.ascii_lowercase, k=4))}"
            client = MqttStressClient(client_id, host, port, topic_prefix, messages, interval)
            batch_clients.append(client.run())
        
        clients.extend(batch_clients)
        print(f"  Created batch {batch_start//batch_size + 1}: clients {batch_start}-{batch_end-1}")
        
        # Small delay between batches
        await asyncio.sleep(0.5)
    
    print(f"\nStarting {len(clients)} client tasks...")
    start_time = time.time()
    
    # Run all clients
    await asyncio.gather(*clients, return_exceptions=True)
    
    elapsed = time.time() - start_time
    stats_task.cancel()
    
    # Final stats
    print(f"""
╔══════════════════════════════════════════════════════════════╗
║           Final Results                                      ║
╠══════════════════════════════════════════════════════════════╣
║  Total Time: {elapsed:.2f}s
║  Connected: {stats['connected']} / {num_clients}
║  Connect Failed: {stats['connect_failed']}
║  Messages Sent: {stats['messages_sent']}
║  Messages Received: {stats['messages_received']}
║  Publish Failed: {stats['publish_failed']}
║  Avg Throughput: {stats['messages_sent']/elapsed:.1f} msg/s
║  Connection Rate: {stats['connected']/elapsed:.1f} conn/s
╚══════════════════════════════════════════════════════════════╝
""")

def main():
    parser = argparse.ArgumentParser(description="DynaMQ MQTT Stress Test")
    parser.add_argument("-H", "--host", default=DEFAULT_HOST, help=f"MQTT broker host (default: {DEFAULT_HOST})")
    parser.add_argument("-p", "--port", type=int, default=DEFAULT_PORT, help=f"MQTT broker port (default: {DEFAULT_PORT})")
    parser.add_argument("-c", "--clients", type=int, default=DEFAULT_CLIENTS, help=f"Number of clients (default: {DEFAULT_CLIENTS})")
    parser.add_argument("-m", "--messages", type=int, default=DEFAULT_MESSAGES_PER_CLIENT, help=f"Messages per client (default: {DEFAULT_MESSAGES_PER_CLIENT})")
    parser.add_argument("-i", "--interval", type=float, default=DEFAULT_MESSAGE_INTERVAL, help=f"Message interval in seconds (default: {DEFAULT_MESSAGE_INTERVAL})")
    parser.add_argument("-t", "--topic", default=DEFAULT_TOPIC_PREFIX, help=f"Topic prefix (default: {DEFAULT_TOPIC_PREFIX})")
    
    args = parser.parse_args()
    
    asyncio.run(run_stress_test(
        args.host, args.port, args.clients, 
        args.messages, args.interval, args.topic
    ))

if __name__ == "__main__":
    main()
