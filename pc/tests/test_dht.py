"""Twelve DHT nodes on loopback: what one stores, another finds; forged entries and phones are kept out."""
import asyncio
import hashlib

from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey

from mobile.dht import Node, is_secure, public, secure_id


class _Port(asyncio.DatagramProtocol):
    def __init__(self, node_box):
        self.node_box = node_box

    def datagram_received(self, data, address):
        self.node_box[0].received(data, address)


async def network(count, *, read_only=()):
    loop, nodes = asyncio.get_running_loop(), []
    for i in range(count):
        box = [None]
        transport, _ = await loop.create_datagram_endpoint(lambda: _Port(box), local_addr=("127.0.0.1", 0))
        box[0] = Node(transport.sendto, local=True, bootstrap=(), read_only=i in read_only)
        box[0].addr = transport.get_extra_info("sockname")
        nodes.append(box[0])
    for node in nodes:
        await node.lookup(node.id, b"find_node", {b"target": node.id}, start=[nodes[0].addr])
    return nodes


async def test_an_entry_one_node_stores_is_found_by_another():
    nodes = await network(12)
    key = Ed25519PrivateKey.generate()
    assert await nodes[3].put(key, b"pc", b"where the computer is") >= 3
    assert (await nodes[9].get(public(key), b"pc"))[1] == b"where the computer is"
    assert await nodes[9].get(public(key), b"an") is None


async def test_a_forged_entry_is_ignored():
    nodes = await network(10)
    key = Ed25519PrivateKey.generate()
    await nodes[1].put(key, b"pc", b"real")
    target = hashlib.sha1(public(key) + b"pc").digest()
    for node in nodes:                                    # every node that keeps it was tampered with
        if target in node.items:
            node.items[target]["v"] = b"forged"
    assert await nodes[7].get(public(key), b"pc") is None


async def test_a_phone_is_a_read_only_node():
    nodes = await network(10, read_only={9})
    phone = nodes[9]
    assert all(phone.id not in node.table for node in nodes[:9])
    key = Ed25519PrivateKey.generate()
    await nodes[2].put(key, b"pc", b"here")
    assert (await phone.get(public(key), b"pc"))[1] == b"here" and not phone.items


def test_node_ids_follow_the_public_ip_as_bep_42_shows():
    for ip, rand, prefix in [("124.31.75.21", 1, "5fbfbf"), ("21.75.31.124", 86, "5a3ce9"),
                             ("65.23.51.170", 22, "a5d432"), ("84.124.73.14", 65, "1b0321"),
                             ("43.213.53.83", 90, "e56f6c")]:
        node_id = secure_id(ip, bytes(19) + bytes([rand]))
        assert node_id[:2].hex() == prefix[:4] and node_id[2] & 0xF8 == int(prefix[4:], 16) & 0xF8
        assert is_secure(bytes.fromhex(prefix) + bytes(16) + bytes([rand]), ip)
        assert not is_secure(bytes.fromhex(prefix) + bytes(16) + bytes([rand]), "8.8.8.8")
