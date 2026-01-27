"""Tests for directory browsing module."""

import os
from pathlib import Path

import pytest

from ras.sessions.directories import DirectoryBrowser


class TestDirectoryBrowser:
    """Tests for DirectoryBrowser class."""

    @pytest.fixture
    def browser(self, tmp_path: Path) -> DirectoryBrowser:
        """Create browser with tmp_path as root."""
        return DirectoryBrowser(config={"root": str(tmp_path)})


class TestList:
    """Tests for list method."""

    @pytest.mark.asyncio
    async def test_lists_directories_in_root(self, tmp_path: Path):
        """Lists directories in root."""
        (tmp_path / "dir1").mkdir()
        (tmp_path / "dir2").mkdir()
        (tmp_path / "file.txt").write_text("test")

        browser = DirectoryBrowser(config={"root": str(tmp_path)})
        result = await browser.list()

        names = [e.name for e in result.entries]
        assert "dir1" in names
        assert "dir2" in names
        assert "file.txt" not in names  # Files excluded

    @pytest.mark.asyncio
    async def test_lists_subdirectory(self, tmp_path: Path):
        """Lists directories in subdirectory."""
        subdir = tmp_path / "subdir"
        subdir.mkdir()
        (subdir / "nested").mkdir()

        browser = DirectoryBrowser(config={"root": str(tmp_path)})
        result = await browser.list(parent=str(subdir))

        names = [e.name for e in result.entries]
        assert "nested" in names

    @pytest.mark.asyncio
    async def test_includes_full_path(self, tmp_path: Path):
        """Entries include full path."""
        (tmp_path / "mydir").mkdir()

        browser = DirectoryBrowser(config={"root": str(tmp_path)})
        result = await browser.list()

        assert len(result.entries) == 1
        assert result.entries[0].path == str(tmp_path / "mydir")

    @pytest.mark.asyncio
    async def test_hides_hidden_directories_by_default(self, tmp_path: Path):
        """Hidden directories are hidden by default."""
        (tmp_path / ".hidden").mkdir()
        (tmp_path / "visible").mkdir()

        browser = DirectoryBrowser(config={"root": str(tmp_path)})
        result = await browser.list()

        names = [e.name for e in result.entries]
        assert ".hidden" not in names
        assert "visible" in names

    @pytest.mark.asyncio
    async def test_shows_hidden_when_configured(self, tmp_path: Path):
        """Hidden directories shown when configured."""
        (tmp_path / ".hidden").mkdir()

        browser = DirectoryBrowser(config={"root": str(tmp_path), "show_hidden": True})
        result = await browser.list()

        names = [e.name for e in result.entries]
        assert ".hidden" in names

    @pytest.mark.asyncio
    async def test_respects_blacklist(self, tmp_path: Path):
        """Blacklisted directories are excluded."""
        (tmp_path / "allowed").mkdir()
        secret = tmp_path / "secret"
        secret.mkdir()

        browser = DirectoryBrowser(
            config={"root": str(tmp_path), "blacklist": [str(secret)]}
        )
        result = await browser.list()

        names = [e.name for e in result.entries]
        assert "allowed" in names
        assert "secret" not in names

    @pytest.mark.asyncio
    async def test_includes_recent_at_root(self, tmp_path: Path):
        """Recent directories included at root level."""
        browser = DirectoryBrowser(config={"root": str(tmp_path)})
        result = await browser.list(recent=["/path1", "/path2"])

        assert result.recent == ["/path1", "/path2"]

    @pytest.mark.asyncio
    async def test_excludes_recent_in_subdirectory(self, tmp_path: Path):
        """Recent directories not included in subdirectory."""
        subdir = tmp_path / "subdir"
        subdir.mkdir()

        browser = DirectoryBrowser(config={"root": str(tmp_path)})
        result = await browser.list(parent=str(subdir), recent=["/path1"])

        assert result.recent == []

    @pytest.mark.asyncio
    async def test_rejects_parent_outside_root(self, tmp_path: Path):
        """Parent outside root returns empty list."""
        browser = DirectoryBrowser(config={"root": str(tmp_path)})
        result = await browser.list(parent="/etc")

        assert len(result.entries) == 0

    @pytest.mark.asyncio
    async def test_handles_nonexistent_parent(self, tmp_path: Path):
        """Nonexistent parent returns empty list."""
        browser = DirectoryBrowser(config={"root": str(tmp_path)})
        result = await browser.list(parent=str(tmp_path / "nonexistent"))

        assert len(result.entries) == 0

    @pytest.mark.asyncio
    async def test_sorts_alphabetically(self, tmp_path: Path):
        """Directories are sorted alphabetically."""
        (tmp_path / "zebra").mkdir()
        (tmp_path / "apple").mkdir()
        (tmp_path / "mango").mkdir()

        browser = DirectoryBrowser(config={"root": str(tmp_path)})
        result = await browser.list()

        names = [e.name for e in result.entries]
        assert names == ["apple", "mango", "zebra"]

    @pytest.mark.asyncio
    async def test_all_entries_are_directories(self, tmp_path: Path):
        """All entries are marked as directories."""
        (tmp_path / "dir1").mkdir()
        (tmp_path / "dir2").mkdir()

        browser = DirectoryBrowser(config={"root": str(tmp_path)})
        result = await browser.list()

        for entry in result.entries:
            assert entry.is_directory is True

    @pytest.mark.asyncio
    async def test_glob_blacklist_pattern(self, tmp_path: Path):
        """Glob patterns in blacklist work."""
        (tmp_path / ".config").mkdir()
        (tmp_path / ".cache").mkdir()
        (tmp_path / "visible").mkdir()

        browser = DirectoryBrowser(
            config={
                "root": str(tmp_path),
                "blacklist": [f"{tmp_path}/.*"],
                "show_hidden": True,  # Would show hidden, but blacklist blocks
            }
        )
        result = await browser.list()

        names = [e.name for e in result.entries]
        assert ".config" not in names
        assert ".cache" not in names
        assert "visible" in names
