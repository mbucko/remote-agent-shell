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
    from ras.daemon_lock import DaemonLock, DaemonAlreadyRunningError

    config = ctx.obj["config"]
    lock_file = Path("~/.config/ras/daemon.lock")
    lock = DaemonLock(lock_file)

    # Acquire lock before starting
    try:
        lock.acquire()
    except DaemonAlreadyRunningError as e:
        click.echo(f"Error: {e}", err=True)
        raise SystemExit(1)

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
    finally:
        lock.release()


@daemon.command()
@click.pass_context
def stop(ctx: click.Context) -> None:
    """Stop the daemon."""
    click.echo("Stopping daemon...")


@daemon.command()
@click.pass_context
def status(ctx: click.Context) -> None:
    """Show daemon status."""
    import os

    from ras.daemon_lock import DaemonLock

    lock_file = Path("~/.config/ras/daemon.lock")
    lock = DaemonLock(lock_file)

    pid = lock.get_owner_pid()
    if pid is None:
        click.echo("Daemon status: not running")
        return

    # Check if the process is actually running
    try:
        os.kill(pid, 0)
        click.echo(f"Daemon status: running (PID {pid})")
    except OSError:
        click.echo("Daemon status: not running (stale lock file)")


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
        import tempfile
        import webbrowser
        import aiohttp

        from ras.pairing.qr_generator import QrGenerator

        config = ctx.obj["config"]
        base_url = f"http://127.0.0.1:{config.port}"
        session_id = None

        try:
            async with aiohttp.ClientSession() as http:
                # 1. Start pairing session via daemon API
                click.echo("Connecting to daemon...")
                try:
                    async with http.post(f"{base_url}/api/pair") as resp:
                        if resp.status == 429:
                            click.echo("Error: Too many pairing sessions active", err=True)
                            return
                        if resp.status != 200:
                            click.echo(f"Error: Failed to start pairing (HTTP {resp.status})", err=True)
                            return

                        data = await resp.json()
                        session_id = data["session_id"]
                        qr_data = data["qr_data"]
                except aiohttp.ClientConnectorError:
                    click.echo("Error: Cannot connect to daemon. Is it running?", err=True)
                    click.echo("Start the daemon with: ras daemon start", err=True)
                    return

                # 2. Create QR generator with proper protobuf encoding
                master_secret = bytes.fromhex(qr_data["master_secret"])
                qr_gen = QrGenerator(
                    ip=qr_data["ip"],
                    port=qr_data["port"],
                    master_secret=master_secret,
                    session_id=qr_data["session_id"],
                    ntfy_topic=qr_data["ntfy_topic"],
                    tailscale_ip=qr_data.get("tailscale_ip", ""),
                    tailscale_port=qr_data.get("tailscale_port", 0),
                )

                click.echo(f"\nPairing session started")
                click.echo(f"Public address: {qr_data['ip']}:{qr_data['port']}")

                # 3. Display QR code
                if browser:
                    html = qr_gen.to_html()
                    with tempfile.NamedTemporaryFile(suffix=".html", delete=False, mode="w") as f:
                        f.write(html)
                        webbrowser.open(f"file://{f.name}")
                    click.echo("QR code opened in browser")
                elif output:
                    qr_gen.to_png(output)
                    click.echo(f"QR code saved to: {output}")
                else:
                    click.echo(qr_gen.to_terminal())
                    click.echo("Scan this QR code with the RemoteAgentShell app")

                # 4. Poll for completion
                click.echo("\nWaiting for device to connect...")
                poll_interval = 1.0
                elapsed = 0.0

                while elapsed < timeout:
                    await asyncio.sleep(poll_interval)
                    elapsed += poll_interval

                    async with http.get(f"{base_url}/api/pair/{session_id}") as resp:
                        if resp.status == 404:
                            click.echo("\nPairing session cancelled", err=True)
                            return
                        if resp.status != 200:
                            continue

                        status = await resp.json()
                        state = status.get("state")

                        if state == "completed":
                            device_name = status.get("device_name", "Unknown")
                            click.echo(f"\nPairing successful! Device: {device_name}")
                            return
                        elif state == "failed":
                            click.echo("\nPairing failed", err=True)
                            return
                        elif state == "expired":
                            click.echo("\nPairing session expired", err=True)
                            return
                        # pending, signaling, authenticating - keep waiting

                click.echo("\nTimeout waiting for device to connect", err=True)

        except KeyboardInterrupt:
            click.echo("\nCancelled")
        finally:
            # Cleanup: cancel session if still pending
            if session_id:
                try:
                    async with aiohttp.ClientSession() as http:
                        await http.delete(f"{base_url}/api/pair/{session_id}")
                except Exception:
                    pass

    asyncio.run(_pair())
