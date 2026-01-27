"""CLI entry point for RAS daemon."""

from pathlib import Path

import click

from ras import __version__
from ras.config import load_config
from ras.logging import setup_logging


@click.group()
@click.option(
    "--config",
    "-c",
    type=click.Path(exists=False, path_type=Path),
    default=None,
    help="Path to config file.",
)
@click.pass_context
def main(ctx: click.Context, config: Path | None) -> None:
    """RemoteAgentShell - Control AI agents from your phone."""
    ctx.ensure_object(dict)
    ctx.obj["config"] = load_config(config)
    ctx.obj["logger"] = setup_logging(ctx.obj["config"])


@main.group()
def daemon() -> None:
    """Daemon control commands."""
    pass


@daemon.command()
@click.pass_context
def start(ctx: click.Context) -> None:
    """Start the daemon."""
    click.echo("Starting daemon...")


@daemon.command()
@click.pass_context
def stop(ctx: click.Context) -> None:
    """Stop the daemon."""
    click.echo("Stopping daemon...")


@daemon.command()
@click.pass_context
def status(ctx: click.Context) -> None:
    """Show daemon status."""
    click.echo("Daemon status: not running")


@main.command()
def version() -> None:
    """Show version."""
    click.echo(f"ras version {__version__}")


@main.group()
def tmux() -> None:
    """tmux management commands."""
    pass


@tmux.command("list")
def tmux_list() -> None:
    """List tmux sessions."""
    import asyncio

    from ras.tmux import TmuxService

    async def _list():
        service = TmuxService()
        try:
            await service.verify()
        except Exception as e:
            click.echo(f"Error: {e}", err=True)
            return

        sessions = await service.list_sessions()
        if not sessions:
            click.echo("No tmux sessions found.")
            return

        click.echo(f"{'ID':<6} {'Name':<20} {'Windows':<8} {'Attached'}")
        click.echo("-" * 50)
        for s in sessions:
            attached = "yes" if s.attached else "no"
            click.echo(f"{s.id:<6} {s.name:<20} {s.windows:<8} {attached}")

    asyncio.run(_list())


@tmux.command("status")
def tmux_status() -> None:
    """Show tmux status."""
    import asyncio
    import re

    from ras.tmux import TmuxService, AsyncCommandExecutor
    from ras.errors import TmuxError, TmuxVersionError

    async def _status():
        executor = AsyncCommandExecutor()
        service = TmuxService(executor=executor)

        # Check if tmux is available
        try:
            stdout, _, _ = await executor.run("tmux", "-V")
            version = stdout.decode().strip()
            click.echo(f"tmux version: {version}")
        except Exception:
            click.echo("tmux: not found", err=True)
            return

        # Check version compatibility
        try:
            await service.verify()
            click.echo("Version: compatible (>= 2.1)")
        except TmuxVersionError as e:
            click.echo(f"Version: incompatible - {e}", err=True)
            return
        except TmuxError as e:
            click.echo(f"Error: {e}", err=True)
            return

        # List sessions
        sessions = await service.list_sessions()
        click.echo(f"Sessions: {len(sessions)}")

    asyncio.run(_status())


@main.command()
@click.option(
    "--timeout",
    "-t",
    type=int,
    default=300,
    help="Timeout in seconds waiting for connection.",
)
@click.pass_context
def pair(ctx: click.Context, timeout: int) -> None:
    """Generate QR code for device pairing."""
    import asyncio

    async def _pair():
        config = ctx.obj["config"]

        from ras.connection import ConnectionInfo
        from ras.signaling import SignalingServer
        from ras.stun import StunClient, StunError

        # Get public IP
        click.echo("Discovering public IP...")
        stun = StunClient(servers=config.stun_servers)
        try:
            ip, _ = await stun.get_public_ip()
        except StunError as e:
            click.echo(f"Error: Could not get public IP: {e}", err=True)
            return

        # Create signaling server and session
        server = SignalingServer(
            host=config.bind_address,
            port=config.port,
            stun_servers=config.stun_servers,
        )
        session_id = server.create_session()

        info = ConnectionInfo(ip=ip, port=config.port, session_id=session_id)

        click.echo(f"\nPublic address: {ip}:{config.port}")
        click.echo(f"Session: {session_id}\n")
        info.print_qr_terminal()
        click.echo("\nScan this QR code with the RemoteAgentShell app")
        click.echo("Waiting for connection...\n")

        # Wait for connection
        connected = asyncio.Event()
        connected_peer = None

        async def on_connected(sid, peer):
            nonlocal connected_peer
            click.echo(f"Device connected! Session: {sid}")
            connected_peer = peer
            connected.set()

        server.on_connected(on_connected)
        await server.start()

        try:
            await asyncio.wait_for(connected.wait(), timeout=timeout)
            click.echo("Pairing successful!")
        except asyncio.TimeoutError:
            click.echo("Timeout waiting for connection", err=True)
        finally:
            await server.close()

    asyncio.run(_pair())
