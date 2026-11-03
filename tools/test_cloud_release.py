#!/usr/bin/env python3
"""Offline release guards: synthetic refs/notes only. No credentials, key access or network."""
import json
import os
from pathlib import Path
import subprocess
import unittest
from unittest.mock import patch

import cloud_release
import project
import test_project


class CloudReleaseTests(unittest.TestCase):
    def setUp(self):
        self.fixture = test_project.ProjectTests()
        self.fixture.setUp()
        self.addCleanup(self.fixture.temporary.cleanup)
        self.root = self.fixture.root
        self.version = project.metadata(lambda n: (self.root / n).read_bytes(), self.fixture.cfg)
        self.tag = 'v' + self.version['app_version']
        self.notes = '# ' + self.version['app_version'] + '\n\n## 新增\n\n- 新增能力。\n\n## 修复\n\n- 无。\n\n## 验证与限制\n\n- 仅离线合成测试，未做设备验证。\n'
        self.name = 'releases/' + self.version['app_version'] + '/NOTES.md'
        self.fixture.write(self.name, self.notes)
        self.fixture.stage()

    def commit(self):
        subprocess.run(['git', '-c', 'user.name=CI Fixture', '-c', 'user.email=ci@example.invalid',
                        'commit', '-qm', 'synthetic source'], cwd=self.root, check=True)
        project.git('update-ref', 'refs/remotes/origin/main', 'HEAD', root=self.root)
        project.git('tag', self.tag, root=self.root)

    def test_current_notes_are_valid(self):
        cloud_release.plan(self.root)

    def test_missing_fixes_rejected(self):
        with self.assertRaises(project.ProjectError):
            cloud_release.notes_text(self.notes.replace('## 修复', '## Other'), self.version['app_version'])

    def test_empty_additions_rejected(self):
        with self.assertRaises(project.ProjectError):
            cloud_release.notes_text(self.notes.replace('- 新增能力。', ''), self.version['app_version'])

    def test_missing_limits_rejected(self):
        with self.assertRaises(project.ProjectError):
            cloud_release.notes_text(self.notes.split('## 验证与限制')[0], self.version['app_version'])

    def test_placeholder_rejected(self):
        with self.assertRaises(project.ProjectError):
            cloud_release.notes_text(self.notes.replace('- 无。', '- TODO'), self.version['app_version'])

    def test_duplicate_section_rejected(self):
        with self.assertRaises(project.ProjectError):
            cloud_release.notes_text(self.notes + '\n## 新增\n- another\n', self.version['app_version'])

    def test_heading_must_match_version(self):
        with self.assertRaises(project.ProjectError):
            cloud_release.notes_text(self.notes, '0.0.0-other')

    def test_missing_notes_file_rejected(self):
        (self.root / self.name).unlink()
        with self.assertRaises(project.ProjectError):
            cloud_release.plan(self.root)

    def test_tag_must_match_source_version(self):
        with self.assertRaises(project.ProjectError):
            cloud_release.plan(self.root, tag='v0.0.0', verify_refs=False)

    def test_valid_tag_and_main_ancestry(self):
        self.commit()
        cloud_release.plan(self.root, tag=self.tag)

    def test_unmerged_tag_is_rejected(self):
        self.commit()
        empty_tree = subprocess.run(['git', 'mktree'], input=b'', cwd=self.root, stdout=subprocess.PIPE, check=True).stdout.strip()
        other = subprocess.run(['git', '-c', 'user.name=Test', '-c', 'user.email=test@example.invalid',
                                'commit-tree', empty_tree.decode(), '-m', 'unrelated'], cwd=self.root,
                                stdout=subprocess.PIPE, check=True).stdout.strip().decode()
        project.git('update-ref', 'refs/remotes/origin/main', other, root=self.root)
        with self.assertRaises(project.ProjectError):
            cloud_release.plan(self.root, tag=self.tag)

    def test_reused_version_code_rejected(self):
        self.commit()
        project.git('tag', 'v0.0.1', root=self.root)
        with self.assertRaises(project.ProjectError):
            cloud_release.plan(self.root, tag=self.tag)

    def test_fork_or_non_tag_signing_rejected(self):
        for environment in ({}, {'GITHUB_ACTIONS': 'true', 'GITHUB_REPOSITORY': 'other/fork', 'GITHUB_REF_TYPE': 'tag', 'GITHUB_REF_NAME': self.tag},
                            {'GITHUB_ACTIONS': 'true', 'GITHUB_REPOSITORY': cloud_release.REPOSITORY, 'GITHUB_REF_TYPE': 'branch'}):
            with patch.dict(os.environ, environment, clear=True), self.assertRaises(project.ProjectError):
                cloud_release.require_ci_tag()

    def test_missing_secrets_fail_before_artifact_or_key_access(self):
        env = {'GITHUB_ACTIONS': 'true', 'GITHUB_REPOSITORY': cloud_release.REPOSITORY,
               'GITHUB_REF_TYPE': 'tag', 'GITHUB_REF_NAME': self.tag}
        with patch.dict(os.environ, env, clear=True), patch.object(cloud_release, 'plan', return_value=(self.fixture.cfg, self.version, self.notes)):
            with self.assertRaisesRegex(project.ProjectError, 'Secrets are not configured'):
                cloud_release.sign()

    def test_signing_secrets_not_inherited_by_plan_subprocesses(self):
        env = {'GITHUB_ACTIONS': 'true', 'GITHUB_REPOSITORY': cloud_release.REPOSITORY,
               'GITHUB_REF_TYPE': 'tag', 'GITHUB_REF_NAME': self.tag,
               'ACEFAN_KEYSTORE_B64': 'dGVzdA==', 'ACEFAN_STORE_PASSWORD': 'fixture'}
        def stopped_plan(**kwargs):
            self.assertNotIn('ACEFAN_KEYSTORE_B64', os.environ)
            self.assertNotIn('ACEFAN_STORE_PASSWORD', os.environ)
            raise project.ProjectError('fixture-stop; no key file is accessed')
        with patch.dict(os.environ, env, clear=True), patch.object(cloud_release, 'plan', side_effect=stopped_plan):
            with self.assertRaisesRegex(project.ProjectError, 'fixture-stop'):
                cloud_release.sign()

    def test_signed_job_is_tag_only_and_allows_manual_retry(self):
        text = (project.ROOT / '.github/workflows/build.yml').read_text()
        self.assertIn("startsWith(github.ref, 'refs/tags/v')", text)
        self.assertIn("github.event_name == 'push' || github.event_name == 'workflow_dispatch'", text)
        block = text[text.index('  signed-draft:'):]
        self.assertNotIn('pull_request', block)
        self.assertIn('environment: release-signing', block)

    def test_descriptive_suffix_is_not_marked_prerelease(self):
        import re
        def is_pre(v):
            return bool(re.search(r'(?i)(?:^|[-.])(?:alpha|beta|rc|pre)(?:[.\d]|$)', v))
        self.assertFalse(is_pre('0.3.0-compat'))
        self.assertTrue(is_pre('0.4.0-beta.1'))
        self.assertTrue(is_pre('0.4.0-rc2'))

    def test_source_links_rewritten_for_release_body(self):
        text = '[limits](../../COMPATIBILITY.md)\n[web](https://example.com)'
        rendered = cloud_release.render_notes(text, self.version, 'a' * 40)
        self.assertIn('/blob/' + 'a' * 40 + '/COMPATIBILITY.md', rendered)
        self.assertIn('[web](https://example.com)', rendered)

    def test_link_cannot_escape_repository(self):
        with self.assertRaises(project.ProjectError):
            cloud_release.render_notes('[bad](../../../escape)', self.version, 'a' * 40)

    def test_workflow_has_no_final_publish_or_key_artifact_upload(self):
        text = (project.ROOT / '.github/workflows/build.yml').read_text()
        self.assertNotIn('pull_request_target', text)
        self.assertIn("environment: release-signing", text)
        self.assertIn("startsWith(github.ref, 'refs/tags/v')", text)
        self.assertIn("if: always()", text)
        self.assertNotIn('path: build/', text)
        self.assertNotIn('gh release edit', text)
        self.assertIn('dist/ci/*-unsigned.apk', text)


if __name__ == '__main__':
    unittest.main()
