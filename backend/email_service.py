"""Transactional account email delivery.

Resend is used when ``RESEND_API_KEY`` is configured.  Local development falls
back to logging the message (including its one-time link) to the backend log so
the complete flow can be tested without an external mail account.
"""

import html
import os
from urllib.parse import urlencode

import requests
from flask import current_app


RESEND_ENDPOINT = "https://api.resend.com/emails"


def _frontend_url():
    return os.environ.get("FRONTEND_URL", "http://localhost:3000").rstrip("/")


def _console_delivery(to, subject, text):
    current_app.logger.warning(
        "EMAIL CONSOLE FALLBACK\nTo: %s\nSubject: %s\n%s",
        to,
        subject,
        text,
    )
    return True


def send_email(to, subject, text, html_body):
    """Send through Resend, with an explicit development console fallback."""

    api_key = os.environ.get("RESEND_API_KEY", "").strip()
    delivery_mode = os.environ.get("EMAIL_DELIVERY_MODE", "").strip().lower()
    console_allowed = (
        delivery_mode == "console"
        or current_app.testing
        or current_app.debug
    )
    if not api_key:
        if console_allowed:
            return _console_delivery(to, subject, text)
        # Do not leak a one-time link into production/Fly logs merely because a
        # secret was omitted. Explicitly set EMAIL_DELIVERY_MODE=console when a
        # non-production environment needs log delivery.
        current_app.logger.error(
            "Account email delivery is not configured (RESEND_API_KEY missing)"
        )
        return False

    sender = os.environ.get(
        "EMAIL_FROM", "EZ-PZ Account Services <onboarding@resend.dev>"
    )
    try:
        response = requests.post(
            RESEND_ENDPOINT,
            headers={
                "Authorization": f"Bearer {api_key}",
                "Content-Type": "application/json",
            },
            json={
                "from": sender,
                "to": [to],
                "subject": subject,
                "text": text,
                "html": html_body,
            },
            timeout=10,
        )
        if 200 <= response.status_code < 300:
            return True

        current_app.logger.error(
            "Resend rejected account email (status=%s): %s",
            response.status_code,
            response.text[:500],
        )
    except requests.RequestException:
        current_app.logger.exception("Resend account email request failed")
    return False


def send_verification_email(user, raw_token):
    query = urlencode({"auth": "verify", "token": raw_token})
    link = f"{_frontend_url()}/?{query}"
    safe_name = html.escape(user.name or "there")
    safe_link = html.escape(link, quote=True)
    return send_email(
        user.email,
        "Verify your EZ-PZ account",
        (
            f"Hello {user.name or 'there'},\n\n"
            f"Verify your account using this link:\n{link}\n\n"
            "This one-time link expires in 24 hours."
        ),
        (
            f"<p>Hello {safe_name},</p>"
            "<p>Verify your EZ-PZ account to finish registration.</p>"
            f'<p><a href="{safe_link}">Verify account</a></p>'
            "<p>This one-time link expires in 24 hours.</p>"
        ),
    )


def send_password_reset_email(user, raw_token):
    query = urlencode({"auth": "reset", "token": raw_token})
    link = f"{_frontend_url()}/?{query}"
    safe_name = html.escape(user.name or "there")
    safe_link = html.escape(link, quote=True)
    return send_email(
        user.email,
        "Reset your EZ-PZ password",
        (
            f"Hello {user.name or 'there'},\n\n"
            f"Reset your password using this link:\n{link}\n\n"
            "This one-time link expires in 60 minutes. If you did not request "
            "this, you can ignore this message."
        ),
        (
            f"<p>Hello {safe_name},</p>"
            "<p>A password reset was requested for your EZ-PZ account.</p>"
            f'<p><a href="{safe_link}">Reset password</a></p>'
            "<p>This one-time link expires in 60 minutes. If you did not "
            "request it, you can ignore this message.</p>"
        ),
    )


def send_welcome_email(user):
    safe_name = html.escape(user.name or "there")
    return send_email(
        user.email,
        "Your EZ-PZ account is active",
        (
            f"Hello {user.name or 'there'},\n\n"
            "Your email has been verified and your EZ-PZ account is active."
        ),
        (
            f"<p>Hello {safe_name},</p>"
            "<p>Your email has been verified and your EZ-PZ account is active.</p>"
        ),
    )


def send_password_changed_email(user):
    safe_name = html.escape(user.name or "there")
    return send_email(
        user.email,
        "Your EZ-PZ password was changed",
        (
            f"Hello {user.name or 'there'},\n\n"
            "Your EZ-PZ password was changed. If you did not make this change, "
            "contact your administrator immediately."
        ),
        (
            f"<p>Hello {safe_name},</p>"
            "<p>Your EZ-PZ password was changed. If you did not make this "
            "change, contact your administrator immediately.</p>"
        ),
    )


def send_new_account_notification(user):
    recipient = os.environ.get("NEW_ACCOUNT_NOTIFY_EMAIL", "").strip()
    if not recipient:
        return True

    safe_name = html.escape(user.name or "Unknown")
    safe_email = html.escape(user.email)
    return send_email(
        recipient,
        "New verified EZ-PZ account",
        f"A new account was verified.\nName: {user.name}\nEmail: {user.email}",
        (
            "<p>A new EZ-PZ account was verified.</p>"
            f"<p><strong>Name:</strong> {safe_name}<br>"
            f"<strong>Email:</strong> {safe_email}</p>"
        ),
    )
