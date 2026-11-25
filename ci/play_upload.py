"""Upload an Android App Bundle to a Google Play track through the Play Developer API."""

import argparse
import sys

from google.auth.transport.requests import AuthorizedSession
from google.oauth2 import service_account

API = 'https://androidpublisher.googleapis.com/androidpublisher/v3/applications'
UPLOAD_API = 'https://androidpublisher.googleapis.com/upload/androidpublisher/v3/applications'
SCOPE = 'https://www.googleapis.com/auth/androidpublisher'


def checked(response):
    if not response.ok:
        sys.exit(f'Play API {response.request.method} {response.url} failed: {response.status_code} {response.text}')
    return response.json()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--credentials', required=True, help='service account JSON key file')
    parser.add_argument('--package', required=True)
    parser.add_argument('--bundle', required=True, help='.aab file')
    parser.add_argument('--track', default='internal', help='internal, alpha, beta, production, or a custom track')
    parser.add_argument('--status', default='completed', choices=['completed', 'draft', 'inProgress', 'halted'])
    parser.add_argument('--release-name', required=True)
    args = parser.parse_args()

    credentials = service_account.Credentials.from_service_account_file(args.credentials, scopes=[SCOPE])
    session = AuthorizedSession(credentials)
    app = f'{API}/{args.package}'

    edit_id = checked(session.post(f'{app}/edits', json={}))['id']
    try:
        with open(args.bundle, 'rb') as bundle:
            uploaded = checked(
                session.post(
                    f'{UPLOAD_API}/{args.package}/edits/{edit_id}/bundles',
                    params={'uploadType': 'media'},
                    headers={'Content-Type': 'application/octet-stream'},
                    data=bundle,
                    timeout=600,
                )
            )
        version_code = str(uploaded['versionCode'])
        release = {'name': args.release_name, 'versionCodes': [version_code], 'status': args.status}
        checked(
            session.put(
                f'{app}/edits/{edit_id}/tracks/{args.track}',
                json={'track': args.track, 'releases': [release]},
            )
        )
        checked(session.post(f'{app}/edits/{edit_id}:commit'))
    except BaseException:
        session.delete(f'{app}/edits/{edit_id}')
        raise
    print(f'Published version code {version_code} to the {args.track} track ({args.status}).')


if __name__ == '__main__':
    main()
