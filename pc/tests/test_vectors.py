import json

from tests.vectors import FILE, vectors


def test_the_android_vectors_are_current():
    assert json.loads(FILE.read_text(encoding="utf-8")) == vectors(), "run: python -m tests.vectors"
