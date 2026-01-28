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
    import asyncio

    from ras.daemon import Daemon, StartupError

    config = ctx.obj["config"]

    async def _start():
        daemon = Daemon(config=config)

        try:
            await daemon.start()
            click.echo(f"Daemon started on port {config.port}")
            click.echo("Press Ctrl+C to stop")
            await daemon.run_forever()
        except StartupError as e:
            click.echo(f"Startup error: {e}", err=True)
            raise SystemExit(1)
        except KeyboardInterrupt:
            click.echo("\nShutting down...")
        finally:
            await daemon.stop()

    try:
        asyncio.run(_start())
    except KeyboardInterrupt:
        pass


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
@click.option(
    "--browser",
    "-b",
    is_flag=True,
    help="Open QR code in browser.",
)
@click.option(
    "--output",
    "-o",
    type=click.Path(),
    default=None,
    help="Save QR code to file.",
)
@click.pass_context
def pair(ctx: click.Context, timeout: int, browser: bool, output: str | None) -> None:
    """Generate QR code for device pairing."""
    import asyncio

    async def _pair():
        config = ctx.obj["config"]

        from ras.pairing import PairingManager, PairingState
        from ras.stun import StunClient, StunError

        # Determine display mode
        if browser:
            display_mode = "browser"
        elif output:
            display_mode = "file"
        else:
            display_mode = "terminal"

        # Create STUN client adapter
        class StunClientAdapter:
            def __init__(self):
                self._client = StunClient(servers=config.stun_servers)

            async def get_public_ip(self) -> str:
                ip, _ = await self._client.get_public_ip()
                return ip

        # Create device store (placeholder)
        class MemoryDeviceStore:
            async def add_device(
                self, device_id: str, device_name: str, master_secret: bytes
            ) -> None:
                pass  # TODO: Implement persistent storage

        click.echo("Discovering public IP...")
        stun_adapter = StunClientAdapter()
        try:
            ip = await stun_adapter.get_public_ip()
            click.echo(f"Public address: {ip}:{config.port}")
        except StunError as e:
            click.echo(f"Error: Could not get public IP: {e}", err=True)
            return

        # Create pairing manager
        manager = PairingManager(
            stun_client=stun_adapter,
            device_store=MemoryDeviceStore(),
            host=config.bind_address,
            port=config.port,
        )

        # Track pairing completion
        pairing_complete = asyncio.Event()
        paired_device = {"id": None, "name": None}

        async def on_complete(device_id: str, device_name: str) -> None:
            paired_device["id"] = device_id
            paired_device["name"] = device_name
            pairing_complete.set()

        manager.on_pairing_complete(on_complete)

        # Start pairing
        session = await manager.start_pairing(
            display_mode=display_mode,
            output_path=output,
        )

        if display_mode == "terminal":
            click.echo("\nScan this QR code with the RemoteAgentShell app")

        # Wait for pairing or timeout
        try:
            await asyncio.wait_for(pairing_complete.wait(), timeout=timeout)
            click.echo(
                f"\nPairing successful! Device: {paired_device['name']}"
            )
        except asyncio.TimeoutError:
            click.echo("\nTimeout waiting for connection", err=True)
        finally:
            await manager.stop()

    asyncio.run(_pair())
