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
