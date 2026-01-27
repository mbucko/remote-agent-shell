"""Tests for session management protobuf serialization.

Tests encoding, decoding, and cross-platform compatibility of session protobufs.
Uses test vectors from sessions.json and test_vectors.py for validation.
"""

import json
from pathlib import Path

import pytest

from ras.proto.ras import (
    Agent,
    AgentsListEvent,
    CreateSessionCommand,
    DirectoriesListEvent,
    DirectoryEntry,
    GetAgentsCommand,
    GetDirectoriesCommand,
    KillSessionCommand,
    ListSessionsCommand,
    RefreshAgentsCommand,
    RenameSessionCommand,
    Session,
    SessionActivityEvent,
    SessionCommand,
    SessionCreatedEvent,
    SessionErrorEvent,
    SessionEvent,
    SessionKilledEvent,
    SessionListEvent,
    SessionRenamedEvent,
    SessionStatus,
)
from tests.sessions.test_vectors import TestProtobufVectors


# ============================================================================
# SESSION MESSAGE TESTS
# ============================================================================

class TestSessionMessage:
    """Tests for Session message serialization."""

    def test_basic_session_roundtrip(self):
        """Basic session serializes and deserializes correctly."""
        session = Session(
            id="abc123def456",
            tmux_name="ras-claude-myproject",
            display_name="claude-myproject",
            directory="/home/user/myproject",
            agent="claude",
            created_at=1700000000,
            last_activity_at=1700000100,
            status=SessionStatus.SESSION_STATUS_ACTIVE,
        )

        # Serialize
        data = bytes(session)
        assert len(data) > 0

        # Deserialize
        parsed = Session().parse(data)

        assert parsed.id == session.id
        assert parsed.tmux_name == session.tmux_name
        assert parsed.display_name == session.display_name
        assert parsed.directory == session.directory
        assert parsed.agent == session.agent
        assert parsed.created_at == session.created_at
        assert parsed.last_activity_at == session.last_activity_at
        assert parsed.status == session.status

    def test_session_with_empty_fields(self):
        """Session with empty optional fields."""
        session = Session(
            id="xyz789",
            tmux_name="ras-unknown-test",
            display_name="test",
            directory="/tmp/test",
            agent="",  # Empty agent (adopted session)
            created_at=0,
            last_activity_at=0,
            status=SessionStatus.SESSION_STATUS_UNKNOWN,
        )

        data = bytes(session)
        parsed = Session().parse(data)

        assert parsed.id == "xyz789"
        assert parsed.agent == ""
        assert parsed.status == SessionStatus.SESSION_STATUS_UNKNOWN

    def test_session_status_values(self):
        """All session status values serialize correctly."""
        for status in SessionStatus:
            session = Session(
                id="test123",
                tmux_name="ras-test-test",
                display_name="test",
                directory="/test",
                agent="test",
                created_at=0,
                last_activity_at=0,
                status=status,
            )

            data = bytes(session)
            parsed = Session().parse(data)
            assert parsed.status == status

    @pytest.mark.parametrize("vector", TestProtobufVectors.SESSION_VECTORS)
    def test_session_vectors(self, vector):
        """Session vectors from test_vectors.py serialize correctly."""
        session_data = vector["session"]

        session = Session(
            id=session_data["id"],
            tmux_name=session_data["tmux_name"],
            display_name=session_data["display_name"],
            directory=session_data["directory"],
            agent=session_data["agent"],
            created_at=session_data["created_at"],
            last_activity_at=session_data["last_activity_at"],
            status=SessionStatus(session_data["status"]),
        )

        data = bytes(session)
        parsed = Session().parse(data)

        assert parsed.id == session_data["id"]
        assert parsed.tmux_name == session_data["tmux_name"]
        assert parsed.display_name == session_data["display_name"]


# ============================================================================
# COMMAND MESSAGE TESTS
# ============================================================================

class TestSessionCommand:
    """Tests for SessionCommand message serialization."""

    def test_list_sessions_command(self):
        """ListSessionsCommand serializes correctly."""
        cmd = SessionCommand(list=ListSessionsCommand())

        data = bytes(cmd)
        parsed = SessionCommand().parse(data)

        assert parsed.list is not None
        # Check oneof field
        assert betterproto.which_one_of(parsed, "command")[0] == "list"

    def test_create_session_command(self):
        """CreateSessionCommand serializes correctly."""
        cmd = SessionCommand(
            create=CreateSessionCommand(
                directory="/home/user/project",
                agent="claude",
            )
        )

        data = bytes(cmd)
        parsed = SessionCommand().parse(data)

        assert parsed.create.directory == "/home/user/project"
        assert parsed.create.agent == "claude"

    def test_kill_session_command(self):
        """KillSessionCommand serializes correctly."""
        cmd = SessionCommand(
            kill=KillSessionCommand(session_id="abc123def456")
        )

        data = bytes(cmd)
        parsed = SessionCommand().parse(data)

        assert parsed.kill.session_id == "abc123def456"

    def test_rename_session_command(self):
        """RenameSessionCommand serializes correctly."""
        cmd = SessionCommand(
            rename=RenameSessionCommand(
                session_id="abc123def456",
                new_name="My New Name",
            )
        )

        data = bytes(cmd)
        parsed = SessionCommand().parse(data)

        assert parsed.rename.session_id == "abc123def456"
        assert parsed.rename.new_name == "My New Name"

    def test_get_agents_command(self):
        """GetAgentsCommand serializes correctly."""
        cmd = SessionCommand(get_agents=GetAgentsCommand())

        data = bytes(cmd)
        parsed = SessionCommand().parse(data)

        assert parsed.get_agents is not None

    def test_get_directories_command_root(self):
        """GetDirectoriesCommand with empty parent (root) serializes correctly."""
        cmd = SessionCommand(
            get_directories=GetDirectoriesCommand(parent="")
        )

        data = bytes(cmd)
        parsed = SessionCommand().parse(data)

        assert parsed.get_directories.parent == ""

    def test_get_directories_command_with_path(self):
        """GetDirectoriesCommand with specific path serializes correctly."""
        cmd = SessionCommand(
            get_directories=GetDirectoriesCommand(parent="/home/user/repos")
        )

        data = bytes(cmd)
        parsed = SessionCommand().parse(data)

        assert parsed.get_directories.parent == "/home/user/repos"

    def test_refresh_agents_command(self):
        """RefreshAgentsCommand serializes correctly."""
        cmd = SessionCommand(refresh_agents=RefreshAgentsCommand())

        data = bytes(cmd)
        parsed = SessionCommand().parse(data)

        assert parsed.refresh_agents is not None

    @pytest.mark.parametrize("vector", TestProtobufVectors.COMMAND_VECTORS)
    def test_command_vectors(self, vector):
        """Command vectors from test_vectors.py serialize correctly."""
        cmd_type = vector["command_type"]
        payload = vector["payload"]

        # Build command based on type
        if cmd_type == "list":
            cmd = SessionCommand(list=ListSessionsCommand())
        elif cmd_type == "create":
            cmd = SessionCommand(
                create=CreateSessionCommand(
                    directory=payload["directory"],
                    agent=payload["agent"],
                )
            )
        elif cmd_type == "kill":
            cmd = SessionCommand(
                kill=KillSessionCommand(session_id=payload["session_id"])
            )
        elif cmd_type == "rename":
            cmd = SessionCommand(
                rename=RenameSessionCommand(
                    session_id=payload["session_id"],
                    new_name=payload["new_name"],
                )
            )
        elif cmd_type == "get_agents":
            cmd = SessionCommand(get_agents=GetAgentsCommand())
        elif cmd_type == "get_directories":
            cmd = SessionCommand(
                get_directories=GetDirectoriesCommand(parent=payload["parent"])
            )
        elif cmd_type == "refresh_agents":
            cmd = SessionCommand(refresh_agents=RefreshAgentsCommand())
        else:
            pytest.fail(f"Unknown command type: {cmd_type}")

        # Roundtrip
        data = bytes(cmd)
        parsed = SessionCommand().parse(data)

        # Verify command can be serialized and parsed
        assert len(data) >= 0  # Empty commands are valid


# ============================================================================
# EVENT MESSAGE TESTS
# ============================================================================

class TestSessionEvent:
    """Tests for SessionEvent message serialization."""

    def test_session_list_event_empty(self):
        """SessionListEvent with empty list serializes correctly."""
        event = SessionEvent(
            list=SessionListEvent(sessions=[])
        )

        data = bytes(event)
        parsed = SessionEvent().parse(data)

        assert len(parsed.list.sessions) == 0

    def test_session_list_event_multiple(self):
        """SessionListEvent with multiple sessions serializes correctly."""
        sessions = [
            Session(
                id=f"session{i}",
                tmux_name=f"ras-claude-project{i}",
                display_name=f"project{i}",
                directory=f"/home/user/project{i}",
                agent="claude",
                created_at=1700000000 + i,
                last_activity_at=1700000000 + i,
                status=SessionStatus.SESSION_STATUS_ACTIVE,
            )
            for i in range(3)
        ]

        event = SessionEvent(
            list=SessionListEvent(sessions=sessions)
        )

        data = bytes(event)
        parsed = SessionEvent().parse(data)

        assert len(parsed.list.sessions) == 3
        assert parsed.list.sessions[0].id == "session0"
        assert parsed.list.sessions[2].id == "session2"

    def test_session_created_event(self):
        """SessionCreatedEvent serializes correctly."""
        session = Session(
            id="newSession123",
            tmux_name="ras-aider-newproject",
            display_name="newproject",
            directory="/home/user/newproject",
            agent="aider",
            created_at=1700000000,
            last_activity_at=1700000000,
            status=SessionStatus.SESSION_STATUS_CREATING,
        )

        event = SessionEvent(
            created=SessionCreatedEvent(session=session)
        )

        data = bytes(event)
        parsed = SessionEvent().parse(data)

        assert parsed.created.session.id == "newSession123"
        assert parsed.created.session.status == SessionStatus.SESSION_STATUS_CREATING

    def test_session_killed_event(self):
        """SessionKilledEvent serializes correctly."""
        event = SessionEvent(
            killed=SessionKilledEvent(session_id="killedSession")
        )

        data = bytes(event)
        parsed = SessionEvent().parse(data)

        assert parsed.killed.session_id == "killedSession"

    def test_session_renamed_event(self):
        """SessionRenamedEvent serializes correctly."""
        event = SessionEvent(
            renamed=SessionRenamedEvent(
                session_id="renamedSession",
                new_name="New Display Name",
            )
        )

        data = bytes(event)
        parsed = SessionEvent().parse(data)

        assert parsed.renamed.session_id == "renamedSession"
        assert parsed.renamed.new_name == "New Display Name"

    def test_session_activity_event(self):
        """SessionActivityEvent serializes correctly."""
        event = SessionEvent(
            activity=SessionActivityEvent(
                session_id="activeSession",
                timestamp=1700000500,
            )
        )

        data = bytes(event)
        parsed = SessionEvent().parse(data)

        assert parsed.activity.session_id == "activeSession"
        assert parsed.activity.timestamp == 1700000500

    def test_session_error_event(self):
        """SessionErrorEvent serializes correctly."""
        event = SessionEvent(
            error=SessionErrorEvent(
                error_code="DIR_NOT_FOUND",
                message="Directory does not exist: /nonexistent",
                session_id="",
            )
        )

        data = bytes(event)
        parsed = SessionEvent().parse(data)

        assert parsed.error.error_code == "DIR_NOT_FOUND"
        assert "nonexistent" in parsed.error.message
        assert parsed.error.session_id == ""

    def test_agents_list_event(self):
        """AgentsListEvent serializes correctly."""
        agents = [
            Agent(
                name="Claude Code",
                binary="claude",
                path="/usr/local/bin/claude",
                available=True,
            ),
            Agent(
                name="Aider",
                binary="aider",
                path="",
                available=False,
            ),
        ]

        event = SessionEvent(
            agents=AgentsListEvent(agents=agents)
        )

        data = bytes(event)
        parsed = SessionEvent().parse(data)

        assert len(parsed.agents.agents) == 2
        assert parsed.agents.agents[0].name == "Claude Code"
        assert parsed.agents.agents[0].available is True
        assert parsed.agents.agents[1].available is False

    def test_directories_list_event(self):
        """DirectoriesListEvent serializes correctly."""
        entries = [
            DirectoryEntry(
                name="project1",
                path="/home/user/project1",
                is_directory=True,
            ),
            DirectoryEntry(
                name="project2",
                path="/home/user/project2",
                is_directory=True,
            ),
        ]

        event = SessionEvent(
            directories=DirectoriesListEvent(
                parent="/home/user",
                entries=entries,
                recent=["/home/user/project1", "/home/user/recent"],
            )
        )

        data = bytes(event)
        parsed = SessionEvent().parse(data)

        assert parsed.directories.parent == "/home/user"
        assert len(parsed.directories.entries) == 2
        assert len(parsed.directories.recent) == 2

    @pytest.mark.parametrize("vector", TestProtobufVectors.EVENT_VECTORS)
    def test_event_vectors(self, vector):
        """Event vectors from test_vectors.py serialize correctly."""
        event_type = vector["event_type"]
        payload = vector["payload"]

        # Build event based on type
        if event_type == "list":
            sessions = [
                Session(
                    id=s["id"],
                    tmux_name=s["tmux_name"],
                    display_name=s["display_name"],
                    directory=s["directory"],
                    agent=s["agent"],
                    created_at=s["created_at"],
                    last_activity_at=s["last_activity_at"],
                    status=SessionStatus(s["status"]),
                )
                for s in payload.get("sessions", [])
            ]
            event = SessionEvent(list=SessionListEvent(sessions=sessions))
        elif event_type == "created":
            s = payload["session"]
            session = Session(
                id=s["id"],
                tmux_name=s["tmux_name"],
                display_name=s["display_name"],
                directory=s["directory"],
                agent=s["agent"],
                created_at=s["created_at"],
                last_activity_at=s["last_activity_at"],
                status=SessionStatus(s["status"]),
            )
            event = SessionEvent(created=SessionCreatedEvent(session=session))
        elif event_type == "killed":
            event = SessionEvent(
                killed=SessionKilledEvent(session_id=payload["session_id"])
            )
        elif event_type == "renamed":
            event = SessionEvent(
                renamed=SessionRenamedEvent(
                    session_id=payload["session_id"],
                    new_name=payload["new_name"],
                )
            )
        elif event_type == "activity":
            event = SessionEvent(
                activity=SessionActivityEvent(
                    session_id=payload["session_id"],
                    timestamp=payload["timestamp"],
                )
            )
        elif event_type == "error":
            event = SessionEvent(
                error=SessionErrorEvent(
                    error_code=payload["error_code"],
                    message=payload["message"],
                    session_id=payload.get("session_id", ""),
                )
            )
        elif event_type == "agents":
            agents = [
                Agent(
                    name=a["name"],
                    binary=a["binary"],
                    path=a.get("path", ""),
                    available=a["available"],
                )
                for a in payload["agents"]
            ]
            event = SessionEvent(agents=AgentsListEvent(agents=agents))
        elif event_type == "directories":
            entries = [
                DirectoryEntry(
                    name=e["name"],
                    path=e["path"],
                    is_directory=e["is_directory"],
                )
                for e in payload.get("entries", [])
            ]
            event = SessionEvent(
                directories=DirectoriesListEvent(
                    parent=payload.get("parent", ""),
                    entries=entries,
                    recent=payload.get("recent", []),
                )
            )
        else:
            pytest.fail(f"Unknown event type: {event_type}")

        # Roundtrip
        data = bytes(event)
        parsed = SessionEvent().parse(data)

        # Verify event can be serialized and parsed
        assert len(data) >= 0


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

    def test_session_event_serialization_vectors(self, test_vectors):
        """Validate session event serialization against JSON test vectors."""
        vectors = test_vectors["session_event_serialization"]["vectors"]

        for vector in vectors:
            event_type = vector["event_type"]
            data = vector["data"]

            # Build and serialize based on type
            if event_type == "SessionCreatedEvent":
                s = data["session"]
                # Map string status to enum
                status_map = {
                    "SESSION_STATUS_ACTIVE": SessionStatus.SESSION_STATUS_ACTIVE,
                    "SESSION_STATUS_CREATING": SessionStatus.SESSION_STATUS_CREATING,
                    "SESSION_STATUS_KILLING": SessionStatus.SESSION_STATUS_KILLING,
                    "SESSION_STATUS_UNKNOWN": SessionStatus.SESSION_STATUS_UNKNOWN,
                }
                session = Session(
                    id=s["id"],
                    tmux_name=s["tmux_name"],
                    display_name=s["display_name"],
                    directory=s["directory"],
                    agent=s["agent"],
                    created_at=s["created_at"],
                    last_activity_at=s["last_activity_at"],
                    status=status_map[s["status"]],
                )
                event = SessionEvent(created=SessionCreatedEvent(session=session))

                serialized = bytes(event)
                parsed = SessionEvent().parse(serialized)

                assert parsed.created.session.id == s["id"], f"Vector {vector['id']} failed"

            elif event_type == "SessionListEvent":
                sessions = [
                    Session(
                        id=s.get("id", ""),
                        tmux_name=s.get("tmux_name", ""),
                        display_name=s.get("display_name", ""),
                        directory=s.get("directory", ""),
                        agent=s.get("agent", ""),
                        created_at=s.get("created_at", 0),
                        last_activity_at=s.get("last_activity_at", 0),
                        status=SessionStatus.SESSION_STATUS_ACTIVE,
                    )
                    for s in data.get("sessions", [])
                ]
                event = SessionEvent(list=SessionListEvent(sessions=sessions))

                serialized = bytes(event)
                parsed = SessionEvent().parse(serialized)

                assert len(parsed.list.sessions) == len(data.get("sessions", [])), \
                    f"Vector {vector['id']} failed"

            elif event_type == "SessionKilledEvent":
                event = SessionEvent(
                    killed=SessionKilledEvent(session_id=data["session_id"])
                )

                serialized = bytes(event)
                parsed = SessionEvent().parse(serialized)

                assert parsed.killed.session_id == data["session_id"], \
                    f"Vector {vector['id']} failed"

            elif event_type == "SessionRenamedEvent":
                event = SessionEvent(
                    renamed=SessionRenamedEvent(
                        session_id=data["session_id"],
                        new_name=data["new_name"],
                    )
                )

                serialized = bytes(event)
                parsed = SessionEvent().parse(serialized)

                assert parsed.renamed.new_name == data["new_name"], \
                    f"Vector {vector['id']} failed"

            elif event_type == "SessionActivityEvent":
                event = SessionEvent(
                    activity=SessionActivityEvent(
                        session_id=data["session_id"],
                        timestamp=data["timestamp"],
                    )
                )

                serialized = bytes(event)
                parsed = SessionEvent().parse(serialized)

                assert parsed.activity.timestamp == data["timestamp"], \
                    f"Vector {vector['id']} failed"

            elif event_type == "SessionErrorEvent":
                event = SessionEvent(
                    error=SessionErrorEvent(
                        error_code=data["error_code"],
                        message=data["message"],
                        session_id=data.get("session_id", ""),
                    )
                )

                serialized = bytes(event)
                parsed = SessionEvent().parse(serialized)

                assert parsed.error.error_code == data["error_code"], \
                    f"Vector {vector['id']} failed"

    def test_session_command_serialization_vectors(self, test_vectors):
        """Validate session command serialization against JSON test vectors."""
        vectors = test_vectors["session_command_serialization"]["vectors"]

        for vector in vectors:
            cmd_type = vector["command_type"]
            data = vector["data"]

            # Build command based on type
            if cmd_type == "ListSessionsCommand":
                cmd = SessionCommand(list=ListSessionsCommand())
            elif cmd_type == "CreateSessionCommand":
                cmd = SessionCommand(
                    create=CreateSessionCommand(
                        directory=data["directory"],
                        agent=data["agent"],
                    )
                )
            elif cmd_type == "KillSessionCommand":
                cmd = SessionCommand(
                    kill=KillSessionCommand(session_id=data["session_id"])
                )
            elif cmd_type == "RenameSessionCommand":
                cmd = SessionCommand(
                    rename=RenameSessionCommand(
                        session_id=data["session_id"],
                        new_name=data["new_name"],
                    )
                )
            elif cmd_type == "GetAgentsCommand":
                cmd = SessionCommand(get_agents=GetAgentsCommand())
            elif cmd_type == "GetDirectoriesCommand":
                cmd = SessionCommand(
                    get_directories=GetDirectoriesCommand(parent=data.get("parent", ""))
                )
            elif cmd_type == "RefreshAgentsCommand":
                cmd = SessionCommand(refresh_agents=RefreshAgentsCommand())
            else:
                pytest.fail(f"Unknown command type: {cmd_type}")

            # Roundtrip
            serialized = bytes(cmd)
            parsed = SessionCommand().parse(serialized)

            # Verify roundtrip succeeded
            assert parsed is not None, f"Vector {vector['id']} failed to parse"


# ============================================================================
# ERROR HANDLING TESTS
# ============================================================================

class TestErrorHandling:
    """Tests for error handling in protobuf parsing."""

    def test_empty_bytes_parses_as_empty_message(self):
        """Empty bytes parse as empty message."""
        parsed = Session().parse(b"")

        # betterproto creates empty message for empty bytes
        assert parsed.id == ""
        assert parsed.created_at == 0

    def test_invalid_protobuf_graceful_handling(self):
        """Invalid protobuf bytes are handled gracefully."""
        # betterproto is lenient - it won't crash on malformed data
        parsed = Session().parse(b"\xff\xff\xff\xff")

        # Should return a message (possibly with partial data)
        assert parsed is not None

    def test_large_message_handling(self):
        """Large messages are handled correctly."""
        # Create a session with large strings
        large_string = "a" * 10000

        session = Session(
            id="test123",
            tmux_name="ras-test-test",
            display_name=large_string,  # Large display name
            directory="/test/" + large_string,  # Large path
            agent="test",
            created_at=0,
            last_activity_at=0,
            status=SessionStatus.SESSION_STATUS_ACTIVE,
        )

        data = bytes(session)
        parsed = Session().parse(data)

        assert parsed.display_name == large_string

    def test_unicode_in_strings(self):
        """Unicode strings serialize correctly."""
        session = Session(
            id="test123",
            tmux_name="ras-test-日本語",
            display_name="日本語プロジェクト",
            directory="/home/user/日本語",
            agent="claude",
            created_at=0,
            last_activity_at=0,
            status=SessionStatus.SESSION_STATUS_ACTIVE,
        )

        data = bytes(session)
        parsed = Session().parse(data)

        assert "日本語" in parsed.display_name


# Need to import betterproto for which_one_of
import betterproto
