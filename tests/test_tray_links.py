import unittest

from utils.tray_common import tg_proxy_url


class TrayProxyLinkTests(unittest.TestCase):
    def test_plain_secret_without_fake_tls(self):
        cfg = {
            "host": "127.0.0.1",
            "port": 1443,
            "secret": "ab" * 16,
            "fake_tls_domain": "",
        }
        self.assertEqual(
            tg_proxy_url(cfg),
            "tg://proxy?server=127.0.0.1&port=1443&secret=dd" + "ab" * 16,
        )

    def test_ee_link_with_fake_tls_domain(self):
        secret = "a80f6eedbabf25c0a8b98a963d675e5d"
        cfg = {
            "host": "127.0.0.1",
            "port": 12666,
            "secret": secret,
            "fake_tls_domain": "i.mycdn.me",
        }
        self.assertEqual(
            tg_proxy_url(cfg),
            "tg://proxy?server=127.0.0.1&port=12666&secret=ee"
            + secret + "i.mycdn.me".encode().hex(),
        )

    def test_non_ascii_domain_falls_back_to_dd(self):
        secret = "cd" * 16
        cfg = {
            "host": "127.0.0.1",
            "port": 1443,
            "secret": secret,
            "fake_tls_domain": "домен.рф",
        }
        self.assertEqual(
            tg_proxy_url(cfg),
            "tg://proxy?server=127.0.0.1&port=1443&secret=dd" + secret,
        )


if __name__ == "__main__":
    unittest.main()
