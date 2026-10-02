import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { routeUiEdition } from '../src/utils/uiEdition.ts'

test('anonymous advanced referral is route-local, explicit and does not affect signed-in preferences', () => {
  assert.equal(routeUiEdition('classic', '/register', 'advanced', false), 'advanced')
  for (const value of [undefined, null, '', 'classic', 'unknown', ['advanced']]) {
    assert.equal(routeUiEdition('classic', '/register', value, false), 'classic')
  }
  for (const path of ['/home', '/login', '/profile', '/trade']) {
    assert.equal(routeUiEdition('classic', path, 'advanced', false), 'classic')
  }
  assert.equal(routeUiEdition('classic', '/register', 'advanced', true), 'classic')
  assert.equal(routeUiEdition('advanced', '/register', 'classic', true), 'advanced')
})

test('only advanced invitations opt into advanced anonymous registration', () => {
  const read = relative => readFileSync(new URL(relative, import.meta.url), 'utf8')
  assert.match(read('../src/advanced/views/Invite.vue'), /query: \{ invite: inviteCode\.value, edition: 'advanced' \}/)
  assert.doesNotMatch(read('../src/views/Invite.vue'), /edition: 'advanced'/)
  assert.match(read('../src/App.vue'), /routeUiEdition\(ui\.edition, route\.path, route\.query\.edition, !!auth\.token\)/)
})
