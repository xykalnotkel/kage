#!/usr/bin/env python3
"""
Independent reference implementation of the SPAKE2 used by adb wireless pairing, ported from
BoringSSL crypto/curve25519/spake25519.cc. Used to generate deterministic cross-language test
vectors for the Kage Java port (manager/src/test/resources/spake2_vectors.json).

Run:  python3 scripts/spake2_reference.py manager/src/test/resources/spake2_vectors.json

Pure python ed25519 group math (RFC 8032 style), no external dependencies.
"""
import hashlib
import json
import sys

p = 2**255 - 19
L = 2**252 + 27742317777372353535851937790883648493


def inv(x):
    return pow(x, p - 2, p)


d = -121665 * inv(121666) % p
I = pow(2, (p - 1) // 4, p)  # sqrt(-1) mod p


def edwards_add(P, Q):
    x1, y1, z1, t1 = P
    x2, y2, z2, t2 = Q
    a = (y1 - x1) * (y2 - x2) % p
    b = (y1 + x1) * (y2 + x2) % p
    c = t1 * 2 * d * t2 % p
    dd = z1 * 2 * z2 % p
    e = b - a
    f = dd - c
    g = dd + c
    h = b + a
    return (e * f % p, g * h % p, f * g % p, e * h % p)


def edwards_sub(P, Q):
    x, y, z, t = Q
    return edwards_add(P, (-x % p, y, z, -t % p))


def scalarmult(P, e):
    q = (0, 1, 1, 0)
    while e > 0:
        if e & 1:
            q = edwards_add(q, P)
        P = edwards_add(P, P)
        e >>= 1
    return q


def recover_x(y, sign):
    if y >= p:
        raise ValueError("y out of range")
    x2 = (y * y - 1) * inv(d * y * y + 1) % p
    if x2 == 0:
        if sign:
            raise ValueError("invalid point")
        return 0
    x = pow(x2, (p + 3) // 8, p)
    if (x * x - x2) % p != 0:
        x = x * I % p
    if (x * x - x2) % p != 0:
        raise ValueError("not on curve")
    if x & 1 != sign:
        x = p - x
    return x


def decodepoint(s):
    sign = s[31] >> 7
    y = int.from_bytes(s, "little") & ((1 << 255) - 1)
    x = recover_x(y, sign)
    return (x, y, 1, x * y % p)


def encodepoint(P):
    x, y, z, t = P
    zi = inv(z)
    x = x * zi % p
    y = y * zi % p
    return int.to_bytes(y | ((x & 1) << 255), 32, "little")


# y = 4/5
BASE = (recover_x(4 * inv(5) % p, 0), 4 * inv(5) % p, 1, recover_x(4 * inv(5) % p, 0) * 4 * inv(5) % p)

POINT_M = decodepoint(bytes.fromhex("5ada7e4bf6ddd9adb6626d32131c6b5c51a1e347a3478f53cfcf441b88eed12e"))
POINT_N = decodepoint(bytes.fromhex("10e3df0ae37d8e7a99b5fe74b44672103dbddcbd06af680d71329a11693bc778"))

NAME_CLIENT = b"adb pair client\x00"
NAME_SERVER = b"adb pair server\x00"


class Spake2Ref:
    def __init__(self, role, seed64):
        assert role in ("alice", "bob")
        self.role = role
        self.my_name, self.their_name = (NAME_CLIENT, NAME_SERVER) if role == "alice" else (NAME_SERVER, NAME_CLIENT)
        # private_tmp = rand(64) -> sc_reduce -> left_shift_3
        self.priv = int.from_bytes(seed64, "little") % L
        self.priv = self.priv << 3  # no mod: the C code shifts the 32-byte LE representation
        self.password_hash = None
        self.password_scalar = None
        self.my_msg = None

    def generate_msg(self, password):
        P = scalarmult(BASE, self.priv)
        self.password_hash = hashlib.sha512(password).digest()
        s = int.from_bytes(self.password_hash, "little") % L
        # the "password scalar hack": make it a multiple of eight by adding order multiples,
        # testing the bits of the running value exactly like the C code
        if s & 1:
            s = s + L
        if s & 2:
            s = s + 2 * L
        if s & 4:
            s = s + 4 * L
        self.password_scalar = s
        mask_pt = POINT_M if self.role == "alice" else POINT_N
        mask = scalarmult(mask_pt, s)
        self.my_msg = encodepoint(edwards_add(P, mask))
        return self.my_msg

    def process_msg(self, their_msg):
        assert len(their_msg) == 32
        Q = decodepoint(their_msg)
        peers_mask = scalarmult(POINT_N if self.role == "alice" else POINT_M, self.password_scalar)
        Qext = edwards_sub(Q, peers_mask)
        dh = scalarmult(Qext, self.priv)
        dh_enc = encodepoint(dh)

        sha = hashlib.sha512()

        def upl(data):
            sha.update(len(data).to_bytes(8, "little"))
            sha.update(data)

        if self.role == "alice":
            upl(self.my_name)
            upl(self.their_name)
            upl(self.my_msg)
            upl(their_msg)
        else:
            upl(self.their_name)
            upl(self.my_name)
            upl(their_msg)
            upl(self.my_msg)
        upl(dh_enc)
        upl(self.password_hash)
        return sha.digest()


def main(out_path):
    vectors = []
    cases = [
        ("123456", b"\x01" * 64, b"\x02" * 64),
        ("123456", bytes(range(64)), bytes(range(64, 128))),
        ("654321", hashlib.sha256(b"a").digest() * 2, hashlib.sha256(b"b").digest() * 2),
        ("000000", b"\xff" * 64, b"\x80" + b"\x7f" * 63),
    ]
    for password, seed_a, seed_b in cases:
        pw_bytes = password.encode("ascii")
        alice = Spake2Ref("alice", seed_a)
        bob = Spake2Ref("bob", seed_b)
        msg_a = alice.generate_msg(pw_bytes)
        msg_b = bob.generate_msg(pw_bytes)
        key_a = alice.process_msg(msg_b)
        key_b = bob.process_msg(msg_a)
        assert key_a == key_b, "reference implementation disagrees with itself"
        vectors.append({
            "password": password,
            "seedA": seed_a.hex(),
            "seedB": seed_b.hex(),
            "msgA": msg_a.hex(),
            "msgB": msg_b.hex(),
            "keyA": key_a.hex(),
            "keyB": key_b.hex(),
        })

    with open(out_path, "w") as f:
        json.dump({"vectors": vectors}, f, indent=2)
    print("wrote %d vectors to %s" % (len(vectors), out_path))


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "spake2_vectors.json")
