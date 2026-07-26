#!/usr/bin/env python3
"""
Email MCP Server — stdio transport, JSON-RPC 2.0, pure stdlib.

Exposes tools:
  - send_email        Send a plain text or HTML email via SMTP.
  - send_email_batch  Send the same email to multiple recipients.

Configuration via environment variables:
  SMTP_HOST      SMTP server host (default: smtp.qq.com)
  SMTP_PORT      SMTP server port (default: 465)
  SMTP_USER      SMTP username / sender address
  SMTP_PASSWORD  SMTP password or authorization code
  SMTP_SSL       Use SSL (default: true)
"""

import json
import sys
import os
import smtplib
import uuid
from email.mime.text import MIMEText
from email.mime.multipart import MIMEMultipart
from email.mime.base import MIMEBase
from email import encoders
from typing import Any


# ── MCP Protocol Helpers ──────────────────────────────────────────

def rpc_response(id_: Any, result: Any) -> dict:
    return {"jsonrpc": "2.0", "id": id_, "result": result}

def rpc_error(id_: Any, code: int, message: str) -> dict:
    return {"jsonrpc": "2.0", "id": id_, "error": {"code": code, "message": message}}

def write(msg: dict) -> None:
    """Write a JSON-RPC message to stdout (one line)."""
    sys.stdout.write(json.dumps(msg, ensure_ascii=False) + "\n")
    sys.stdout.flush()

def read() -> dict | None:
    """Read a JSON-RPC message from stdin (one line)."""
    try:
        line = sys.stdin.readline()
        if not line:
            return None
        return json.loads(line)
    except json.JSONDecodeError:
        return None


# ── SMTP Configuration ─────────────────────────────────────────────

SMTP_HOST = os.environ.get("SMTP_HOST", "smtp.qq.com")
SMTP_PORT = int(os.environ.get("SMTP_PORT", "465"))
SMTP_USER = os.environ.get("SMTP_USER", "")
SMTP_PASSWORD = os.environ.get("SMTP_PASSWORD", "")
SMTP_SSL = os.environ.get("SMTP_SSL", "true").lower() == "true"


# ── Tool Definitions ────────────────────────────────────────────────

TOOLS = [
    {
        "name": "send_email",
        "description": "Send an email to one or more recipients. Supports plain text and HTML body.",
        "inputSchema": {
            "type": "object",
            "properties": {
                "to": {
                    "type": "string",
                    "description": "Recipient email address(es). Multiple addresses separated by comma.",
                },
                "subject": {
                    "type": "string",
                    "description": "Email subject line.",
                },
                "body": {
                    "type": "string",
                    "description": "Email body content. Can be plain text or HTML.",
                },
                "is_html": {
                    "type": "boolean",
                    "description": "Whether the body is HTML. Default false (plain text).",
                },
                "cc": {
                    "type": "string",
                    "description": "CC recipient(s), comma-separated. Optional.",
                },
            },
            "required": ["to", "subject", "body"],
        },
    },
    {
        "name": "send_email_batch",
        "description": "Send the same email to multiple recipients individually (BCC mode, each recipient sees only themselves).",
        "inputSchema": {
            "type": "object",
            "properties": {
                "recipients": {
                    "type": "string",
                    "description": "JSON array of email addresses, e.g. [\"a@x.com\",\"b@x.com\"]",
                },
                "subject": {"type": "string", "description": "Email subject line."},
                "body": {"type": "string", "description": "Email body content."},
                "is_html": {"type": "boolean", "description": "Whether the body is HTML."},
            },
            "required": ["recipients", "subject", "body"],
        },
    },
]


# ── Tool Implementations ────────────────────────────────────────────

def _get_smtp_connection():
    """Create and return an SMTP connection."""
    if SMTP_SSL:
        server = smtplib.SMTP_SSL(SMTP_HOST, SMTP_PORT, timeout=30)
    else:
        server = smtplib.SMTP(SMTP_HOST, SMTP_PORT, timeout=30)
        server.starttls()
    server.login(SMTP_USER, SMTP_PASSWORD)
    return server


def tool_send_email(args: dict) -> str:
    if not SMTP_USER or not SMTP_PASSWORD:
        return "Error: SMTP credentials not configured. Set SMTP_USER and SMTP_PASSWORD environment variables."

    to_addr = args["to"]
    subject = args["subject"]
    body = args["body"]
    is_html = args.get("is_html", False)
    cc = args.get("cc", "")

    subtype = "html" if is_html else "plain"
    msg = MIMEText(body, subtype, "utf-8")
    msg["From"] = SMTP_USER
    msg["To"] = to_addr
    msg["Subject"] = subject
    if cc:
        msg["Cc"] = cc

    try:
        server = _get_smtp_connection()
        all_recipients = [a.strip() for a in to_addr.split(",")]
        if cc:
            all_recipients += [a.strip() for a in cc.split(",")]
        server.sendmail(SMTP_USER, all_recipients, msg.as_string())
        server.quit()
        return f"Email sent successfully to {to_addr}" + (f" (CC: {cc})" if cc else "")
    except Exception as e:
        return f"Failed to send email: {e}"


def tool_send_email_batch(args: dict) -> str:
    if not SMTP_USER or not SMTP_PASSWORD:
        return "Error: SMTP credentials not configured."

    try:
        recipients = json.loads(args["recipients"])
    except json.JSONDecodeError:
        return "Error: 'recipients' must be a JSON array string, e.g. '[\"a@x.com\",\"b@x.com\"]'"

    subject = args["subject"]
    body = args["body"]
    is_html = args.get("is_html", False)
    subtype = "html" if is_html else "plain"

    success = []
    failed = []
    for recipient in recipients:
        try:
            msg = MIMEText(body, subtype, "utf-8")
            msg["From"] = SMTP_USER
            msg["To"] = recipient
            msg["Subject"] = subject

            server = _get_smtp_connection()
            server.sendmail(SMTP_USER, [recipient.strip()], msg.as_string())
            server.quit()
            success.append(recipient)
        except Exception as e:
            failed.append(f"{recipient}: {e}")

    result = f"Batch send complete. Success: {len(success)}, Failed: {len(failed)}."
    if failed:
        result += f"\nFailures: {', '.join(failed)}"
    return result


TOOL_HANDLERS = {
    "send_email": tool_send_email,
    "send_email_batch": tool_send_email_batch,
}


# ── Server Info ─────────────────────────────────────────────────────

SERVER_INFO = {
    "name": "email-mcp-server",
    "version": "1.0.0",
}

SERVER_CAPABILITIES = {
    "tools": {},
}


# ── Main Loop ───────────────────────────────────────────────────────

def main():
    while True:
        request = read()
        if request is None:
            break  # stdin closed

        method = request.get("method", "")
        req_id = request.get("id")
        params = request.get("params", {})

        # --- initialize ---
        if method == "initialize":
            write(rpc_response(req_id, {
                "protocolVersion": "2024-11-05",
                "serverInfo": SERVER_INFO,
                "capabilities": SERVER_CAPABILITIES,
            }))

        # --- notifications/initialized ---
        elif method == "notifications/initialized":
            pass  # no response needed

        # --- tools/list ---
        elif method == "tools/list":
            write(rpc_response(req_id, {"tools": TOOLS}))

        # --- tools/call ---
        elif method == "tools/call":
            tool_name = params.get("name", "")
            tool_args = params.get("arguments", {})
            handler = TOOL_HANDLERS.get(tool_name)

            if handler:
                try:
                    result_text = handler(tool_args)
                    write(rpc_response(req_id, {
                        "content": [{"type": "text", "text": result_text}],
                    }))
                except Exception as e:
                    write(rpc_response(req_id, {
                        "content": [{"type": "text", "text": f"Tool execution error: {e}"}],
                        "isError": True,
                    }))
            else:
                write(rpc_error(req_id, -32601, f"Unknown tool: {tool_name}"))

        # --- ping ---
        elif method == "ping":
            write(rpc_response(req_id, {}))

        # --- unknown ---
        else:
            write(rpc_error(req_id, -32601, f"Method not found: {method}"))


if __name__ == "__main__":
    main()