"""Tests for directory access control and filtering.

Tests whitelist/blacklist filtering, path traversal prevention, and symlink handling.
Uses test vectors from sessions.json for cross-platform validation.
"""

import json
import os
import tempfile
from pathlib import Path
from unittest.mock import MagicMock, patch

import pytest

from tests.sessions.test_vectors import TestDirectoryPathVectors


# ============================================================================
# DIRECTORY FILTER IMPLEMENTATION (to be implemented in ras.sessions module)
# ============================================================================

class DirectoryFilter:
    """Filters directory access based on whitelist/blacklist rules.

    Attributes:
        root: The root directory (all paths must be under this).
        whitelist: List of allowed directories (if empty, all under root allowed).
        blacklist: List of denied directories.
    """

    def __init__(
        self,
        root: str = "~",
        whitelist: list[str] | None = None,
        blacklist: list[str] | None = None,
    ):
        """Initialize directory filter.

        Args:
            root: Root directory (paths must be under this).
            whitelist: Allowed directories. If empty, all under root allowed.
            blacklist: Denied directories. Always checked.
        """
        self.root = os.path.realpath(os.path.expanduser(root))
        self.whitelist = [
            os.path.realpath(os.path.expanduser(p))
            for p in (whitelist or [])
        ]
        self.blacklist = [
            os.path.realpath(os.path.expanduser(p))
            for p in (blacklist or [])
        ]

    def is_allowed(self, path: str) -> bool:
        """Check if a path is allowed.

        Args:
            path: Path to check.

        Returns:
            True if allowed, False otherwise.
        """
        try:
            # Resolve to absolute, canonical path
            resolved = os.path.realpath(os.path.expanduser(path))

            # Must be under root
            if not self._is_under(resolved, self.root):
                return False

            # Check blacklist first
            for blacklisted in self.blacklist:
                if self._is_under(resolved, blacklisted):
                    return False

            # Check whitelist (if configured)
            if self.whitelist:
                allowed = False
                for whitelisted in self.whitelist:
                    if self._is_under(resolved, whitelisted):
                        allowed = True
                        break
                if not allowed:
                    return False

            return True

        except (OSError, ValueError):
            return False

    def _is_under(self, path: str, parent: str) -> bool:
        """Check if path is under parent directory.

        Args:
            path: Path to check.
            parent: Parent directory.

        Returns:
            True if path is under or equal to parent.
        """
        # Handle exact match
        if path == parent:
            return True

        # Check if path starts with parent + separator
        return path.startswith(parent + os.sep)

    def list_directories(self, parent: str) -> list[dict]:
        """List child directories of a path.

        Args:
            parent: Parent directory to list.

        Returns:
            List of directory entries with name, path, is_directory.

        Raises:
            PermissionError: If parent is not allowed.
            FileNotFoundError: If parent does not exist.
        """
        if parent and not self.is_allowed(parent):
            raise PermissionError(f"Directory not allowed: {parent}")

        resolved = os.path.realpath(os.path.expanduser(parent or self.root))

        if not os.path.exists(resolved):
            raise FileNotFoundError(f"Directory not found: {resolved}")

        if not os.path.isdir(resolved):
            raise NotADirectoryError(f"Not a directory: {resolved}")

        entries = []
        try:
            for name in sorted(os.listdir(resolved)):
                # Skip hidden files by default
                if name.startswith("."):
                    continue

                full_path = os.path.join(resolved, name)

                # Skip if not a directory
                if not os.path.isdir(full_path):
                    continue

                # Skip if blacklisted
                if not self.is_allowed(full_path):
                    continue

                entries.append({
                    "name": name,
                    "path": full_path,
                    "is_directory": True,
                })
        except PermissionError:
            pass  # Can't read directory, return empty

        return entries


# ============================================================================
# BASIC FILTERING TESTS
# ============================================================================

class TestDirectoryFilterBasic:
    """Basic directory filter tests."""

    @pytest.fixture
    def temp_dir_structure(self, tmp_path):
        """Create a temporary directory structure for testing."""
        # Create structure:
        # tmp/
        #   root/
        #     repos/
        #       project1/
        #       project2/
        #       secret/
        #     projects/
        #       app/
        #     .ssh/
        #     documents/
        root = tmp_path / "root"
        (root / "repos" / "project1").mkdir(parents=True)
        (root / "repos" / "project2").mkdir(parents=True)
        (root / "repos" / "secret").mkdir(parents=True)
        (root / "projects" / "app").mkdir(parents=True)
        (root / ".ssh").mkdir(parents=True)
        (root / "documents").mkdir(parents=True)

        return root

    def test_all_under_root_when_no_whitelist(self, temp_dir_structure):
        """All paths under root allowed when whitelist is empty."""
        root = temp_dir_structure
        filter = DirectoryFilter(root=str(root), whitelist=[], blacklist=[])

        assert filter.is_allowed(str(root / "repos" / "project1")) is True
        assert filter.is_allowed(str(root / "documents")) is True

    def test_whitelist_restricts_access(self, temp_dir_structure):
        """Whitelist restricts access to listed directories."""
        root = temp_dir_structure
        filter = DirectoryFilter(
            root=str(root),
            whitelist=[str(root / "repos")],
            blacklist=[],
        )

        assert filter.is_allowed(str(root / "repos" / "project1")) is True
        assert filter.is_allowed(str(root / "documents")) is False

    def test_blacklist_denies_access(self, temp_dir_structure):
        """Blacklist denies access to listed directories."""
        root = temp_dir_structure
        filter = DirectoryFilter(
            root=str(root),
            whitelist=[],
            blacklist=[str(root / "repos" / "secret")],
        )

        assert filter.is_allowed(str(root / "repos" / "project1")) is True
        assert filter.is_allowed(str(root / "repos" / "secret")) is False

    def test_blacklist_overrides_whitelist(self, temp_dir_structure):
        """Blacklist takes precedence over whitelist."""
        root = temp_dir_structure
        filter = DirectoryFilter(
            root=str(root),
            whitelist=[str(root / "repos")],
            blacklist=[str(root / "repos" / "secret")],
        )

        assert filter.is_allowed(str(root / "repos" / "project1")) is True
        assert filter.is_allowed(str(root / "repos" / "secret")) is False

    def test_outside_root_denied(self, temp_dir_structure):
        """Paths outside root are always denied."""
        root = temp_dir_structure
        filter = DirectoryFilter(root=str(root), whitelist=[], blacklist=[])

        assert filter.is_allowed("/etc/passwd") is False
        assert filter.is_allowed("/var/log") is False

    def test_child_of_whitelisted(self, temp_dir_structure):
        """Children of whitelisted directories are allowed."""
        root = temp_dir_structure
        filter = DirectoryFilter(
            root=str(root),
            whitelist=[str(root / "repos")],
            blacklist=[],
        )

        assert filter.is_allowed(str(root / "repos" / "project1")) is True
        assert filter.is_allowed(str(root / "repos" / "project1" / "src")) is True

    def test_child_of_blacklisted(self, temp_dir_structure):
        """Children of blacklisted directories are denied."""
        root = temp_dir_structure
        filter = DirectoryFilter(
            root=str(root),
            whitelist=[],
            blacklist=[str(root / "repos" / "secret")],
        )

        # Create a child of secret
        child = root / "repos" / "secret" / "data"
        child.mkdir(parents=True)

        assert filter.is_allowed(str(child)) is False


# ============================================================================
# PATH TRAVERSAL TESTS
# ============================================================================

class TestPathTraversalPrevention:
    """Tests for path traversal attack prevention."""

    @pytest.fixture
    def temp_dir_structure(self, tmp_path):
        """Create a temporary directory structure."""
        root = tmp_path / "home" / "user"
        (root / "repos" / "project").mkdir(parents=True)
        (root / ".ssh").mkdir(parents=True)
        return root

    @pytest.mark.parametrize("path", TestDirectoryPathVectors.PATH_TRAVERSAL_ATTEMPTS)
    def test_path_traversal_attempts_blocked(self, path, temp_dir_structure):
        """Path traversal attempts are blocked."""
        root = temp_dir_structure
        filter = DirectoryFilter(
            root=str(root),
            whitelist=[str(root / "repos")],
            blacklist=[],
        )

        # These should all be denied
        assert filter.is_allowed(path) is False

    def test_relative_path_traversal(self, temp_dir_structure):
        """Relative path traversal is blocked."""
        root = temp_dir_structure
        filter = DirectoryFilter(root=str(root), whitelist=[], blacklist=[])

        # Try to escape root
        assert filter.is_allowed(str(root / "repos" / ".." / ".." / "..")) is False

    def test_resolved_traversal(self, temp_dir_structure):
        """Resolved paths still can't escape."""
        root = temp_dir_structure
        filter = DirectoryFilter(root=str(root), whitelist=[], blacklist=[])

        # Even if we go up and come back, we still can't access outside
        safe_path = str(root / "repos" / ".." / "repos" / "project")
        assert filter.is_allowed(safe_path) is True

        # But going truly outside fails
        escape_path = str(root / ".." / ".." / "etc")
        assert filter.is_allowed(escape_path) is False


# ============================================================================
# SYMLINK TESTS
# ============================================================================

class TestSymlinkHandling:
    """Tests for symlink handling."""

    @pytest.fixture
    def temp_dir_with_symlinks(self, tmp_path):
        """Create directory structure with symlinks."""
        root = tmp_path / "home" / "user"
        (root / "repos" / "project").mkdir(parents=True)
        (root / "external").mkdir(parents=True)

        # Create symlink inside allowed area pointing to allowed target
        safe_link = root / "repos" / "safe-link"
        safe_link.symlink_to(root / "repos" / "project")

        # Create symlink that escapes
        escape_link = root / "repos" / "escape-link"
        escape_link.symlink_to(root / ".." / "..")

        # Create symlink to external directory
        external_link = root / "repos" / "external-link"
        external_link.symlink_to(root / "external")

        return root

    def test_safe_symlink_allowed(self, temp_dir_with_symlinks):
        """Safe symlinks within allowed area are permitted."""
        root = temp_dir_with_symlinks
        filter = DirectoryFilter(
            root=str(root),
            whitelist=[str(root / "repos")],
            blacklist=[],
        )

        # Symlink target is still within repos
        assert filter.is_allowed(str(root / "repos" / "safe-link")) is True

    def test_escape_symlink_blocked(self, temp_dir_with_symlinks):
        """Symlinks that escape root are blocked."""
        root = temp_dir_with_symlinks
        filter = DirectoryFilter(root=str(root), whitelist=[], blacklist=[])

        # Symlink target is outside root
        assert filter.is_allowed(str(root / "repos" / "escape-link")) is False

    def test_symlink_to_non_whitelisted_blocked(self, temp_dir_with_symlinks):
        """Symlinks to non-whitelisted directories are blocked."""
        root = temp_dir_with_symlinks
        filter = DirectoryFilter(
            root=str(root),
            whitelist=[str(root / "repos")],
            blacklist=[],
        )

        # Symlink target is not in whitelist
        assert filter.is_allowed(str(root / "repos" / "external-link")) is False


# ============================================================================
# DIRECTORY LISTING TESTS
# ============================================================================

class TestDirectoryListing:
    """Tests for directory listing functionality."""

    @pytest.fixture
    def temp_dir_structure(self, tmp_path):
        """Create a temporary directory structure."""
        root = tmp_path / "home" / "user"
        (root / "repos" / "project1").mkdir(parents=True)
        (root / "repos" / "project2").mkdir(parents=True)
        (root / "repos" / ".hidden").mkdir(parents=True)
        (root / "repos" / "secret").mkdir(parents=True)

        # Create a file (should not be listed)
        (root / "repos" / "file.txt").touch()

        return root

    def test_list_returns_directories_only(self, temp_dir_structure):
        """Listing returns only directories, not files."""
        root = temp_dir_structure
        filter = DirectoryFilter(root=str(root), whitelist=[], blacklist=[])

        entries = filter.list_directories(str(root / "repos"))

        names = [e["name"] for e in entries]
        assert "file.txt" not in names

    def test_list_excludes_hidden(self, temp_dir_structure):
        """Listing excludes hidden directories by default."""
        root = temp_dir_structure
        filter = DirectoryFilter(root=str(root), whitelist=[], blacklist=[])

        entries = filter.list_directories(str(root / "repos"))

        names = [e["name"] for e in entries]
        assert ".hidden" not in names

    def test_list_excludes_blacklisted(self, temp_dir_structure):
        """Listing excludes blacklisted directories."""
        root = temp_dir_structure
        filter = DirectoryFilter(
            root=str(root),
            whitelist=[],
            blacklist=[str(root / "repos" / "secret")],
        )

        entries = filter.list_directories(str(root / "repos"))

        names = [e["name"] for e in entries]
        assert "secret" not in names

    def test_list_not_allowed_raises(self, temp_dir_structure):
        """Listing non-allowed directory raises PermissionError."""
        root = temp_dir_structure
        filter = DirectoryFilter(
            root=str(root),
            whitelist=[str(root / "repos")],
            blacklist=[],
        )

        with pytest.raises(PermissionError):
            filter.list_directories("/etc")

    def test_list_nonexistent_raises(self, temp_dir_structure):
        """Listing nonexistent directory raises FileNotFoundError."""
        root = temp_dir_structure
        filter = DirectoryFilter(root=str(root), whitelist=[], blacklist=[])

        with pytest.raises(FileNotFoundError):
            filter.list_directories(str(root / "nonexistent"))


# ============================================================================
# CROSS-PLATFORM VALIDATION WITH JSON TEST VECTORS
# ============================================================================

class TestCrossPlatformVectors:
    """Tests using JSON test vectors for cross-platform validation."""

    @pytest.fixture
    def test_vectors(self):
        """Load test vectors from JSON file."""
        vectors_path = Path(__file__).parent.parent.parent.parent / "test-vectors" / "sessions.json"
        with open(vectors_path) as f:
            return json.load(f)

    @pytest.fixture
    def temp_dir_from_config(self, tmp_path, test_vectors):
        """Create directory structure matching test vector config."""
        config = test_vectors["directory_filtering"]["config"]

        # Create directories that mirror the config
        # The config uses /home/user as root
        root = tmp_path / "home" / "user"
        (root / "repos" / "my-project" / "src").mkdir(parents=True)
        (root / "repos" / "secret" / "data").mkdir(parents=True)
        (root / "projects").mkdir(parents=True)
        (root / "documents").mkdir(parents=True)
        (root / ".ssh").mkdir(parents=True)

        return tmp_path

    def test_directory_filtering_vectors(self, test_vectors, temp_dir_from_config):
        """Validate directory filtering against JSON test vectors."""
        config = test_vectors["directory_filtering"]["config"]
        vectors = test_vectors["directory_filtering"]["vectors"]

        # Map /home/user to our temp directory
        real_root = str(temp_dir_from_config / "home" / "user")

        def map_path(path: str) -> str:
            """Map test vector path to real path."""
            if path.startswith("/home/user"):
                return path.replace("/home/user", real_root)
            return path  # Keep external paths like /etc/passwd

        # Build whitelist/blacklist with mapped paths
        whitelist = [map_path(p) for p in config["whitelist"]]
        blacklist = [map_path(p) for p in config["blacklist"]]

        filter = DirectoryFilter(
            root=real_root,
            whitelist=whitelist,
            blacklist=blacklist,
        )

        for vector in vectors:
            path = map_path(vector["path"])
            expected = vector["allowed"]

            result = filter.is_allowed(path)
            assert result == expected, (
                f"Vector '{vector['id']}' failed: "
                f"path '{vector['path']}' expected allowed={expected}, got {result}"
            )

    def test_directory_filtering_no_whitelist_vectors(self, test_vectors, temp_dir_from_config):
        """Validate filtering without whitelist."""
        config = test_vectors["directory_filtering_no_whitelist"]["config"]
        vectors = test_vectors["directory_filtering_no_whitelist"]["vectors"]

        real_root = str(temp_dir_from_config / "home" / "user")

        def map_path(path: str) -> str:
            if path.startswith("/home/user"):
                return path.replace("/home/user", real_root)
            return path

        blacklist = [map_path(p) for p in config["blacklist"]]

        filter = DirectoryFilter(
            root=real_root,
            whitelist=[],  # Empty = all under root allowed
            blacklist=blacklist,
        )

        for vector in vectors:
            path = map_path(vector["path"])
            expected = vector["allowed"]

            result = filter.is_allowed(path)
            assert result == expected, (
                f"Vector '{vector['id']}' failed: "
                f"path '{vector['path']}' expected allowed={expected}, got {result}"
            )


# ============================================================================
# EDGE CASES AND SECURITY
# ============================================================================

class TestSecurityEdgeCases:
    """Security-focused edge case tests."""

    def test_null_byte_injection(self, tmp_path):
        """Null byte injection is handled safely."""
        root = tmp_path / "root"
        root.mkdir()

        filter = DirectoryFilter(root=str(root), whitelist=[], blacklist=[])

        # Null byte should not bypass checks
        malicious_path = str(root) + "/allowed\x00/../../../etc/passwd"
        assert filter.is_allowed(malicious_path) is False

    def test_very_deep_nesting(self, tmp_path):
        """Very deep directory nesting is handled."""
        root = tmp_path / "root"
        deep_path = root
        for i in range(100):
            deep_path = deep_path / f"level{i}"
        deep_path.mkdir(parents=True)

        filter = DirectoryFilter(root=str(root), whitelist=[], blacklist=[])

        assert filter.is_allowed(str(deep_path)) is True

    def test_unicode_path(self, tmp_path):
        """Unicode paths are handled correctly."""
        root = tmp_path / "root"
        unicode_dir = root / "日本語フォルダ"
        unicode_dir.mkdir(parents=True)

        filter = DirectoryFilter(root=str(root), whitelist=[], blacklist=[])

        assert filter.is_allowed(str(unicode_dir)) is True

    def test_special_chars_in_path(self, tmp_path):
        """Paths with special characters work correctly."""
        root = tmp_path / "root"
        special_dir = root / "path with spaces & special (chars)"
        special_dir.mkdir(parents=True)

        filter = DirectoryFilter(root=str(root), whitelist=[], blacklist=[])

        assert filter.is_allowed(str(special_dir)) is True

    @pytest.mark.parametrize("blacklisted", TestDirectoryPathVectors.BLACKLISTED_PATHS)
    def test_common_blacklist_paths_blocked(self, blacklisted, tmp_path):
        """Common sensitive paths are blocked."""
        root = tmp_path / "root"
        root.mkdir()

        # If the blacklisted path is outside root, it should be blocked
        # because it's outside root, not because of blacklist
        filter = DirectoryFilter(root=str(root), whitelist=[], blacklist=[])

        assert filter.is_allowed(blacklisted) is False
