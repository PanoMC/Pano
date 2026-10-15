/*
 * Pano fallback pages (served at /_pano/assets/fallback.js). No dependencies, no build step.
 *
 * Reads the JSON block #pano-fallback-config ({ api, target, home, t, page }) and drives the forms the page
 * templates carry:
 *
 *   form[data-pano-endpoint="/auth/verify-email"]   posts its named fields as JSON to <api><endpoint>
 *     a plugin's own endpoint is written "plugin:/relative/path" (resolved against config.pluginApi, the
 *     /api/plugins/<id> of the plugin that owns the page) or as an absolute /api/plugins/... path; nothing outside
 *     /api is ever called
 *     data-pano-auto                                 posts once when the page loads (a link from a mail)
 *     [data-pano-message]                            where an error is written
 *     [data-pano-hide-on-done] / [data-pano-done]    hidden / shown after a success
 *   [data-pano-flow="login"]                         the sign-in flow with its steps (see initLogin)
 *
 * Every POST asks GET <api>/auth/csrf first and repeats the token in X-CSRF-Token when the visitor already
 * has a session (without one the endpoint answers 401 and nothing is sent). All texts come from config.t.
 */
(function () {
  'use strict'

  var config = {}

  try {
    config = JSON.parse(document.getElementById('pano-fallback-config').textContent) || {}
  } catch (e) {
    config = {}
  }

  var api = config.api || '/api/v1'
  var texts = config.t || {}
  var errors = texts.errors || {}
  // The page's own texts (config.errors, from FallbackPage.errors) win over the core table.
  var pageErrors = config.errors || {}
  var page = config.page || {}
  var csrfToken

  /**
   * The URL an endpoint names, or '' when it is refused. "/x" is core (<api>/x); "plugin:/x" is the owning plugin
   * (<pluginApi>/x, only on a plugin's page); an absolute "/api/..." path is taken as written. A dot segment, a
   * backslash, a second slash, a query or a fragment is refused, so no endpoint can leave /api.
   */
  function resolve(endpoint) {
    if (typeof endpoint !== 'string' || /[\\?#%\s]|\/\/|(^|\/)\.\.?(\/|$)/.test(endpoint.replace(/^plugin:/, ''))) {
      return ''
    }

    if (endpoint.indexOf('plugin:/') === 0) {
      return config.pluginApi && /^\/api\/plugins\/[^/]+$/.test(config.pluginApi) ? config.pluginApi + endpoint.slice(7) : ''
    }

    if (endpoint.indexOf('/api/') === 0) {
      return endpoint
    }

    return endpoint.charAt(0) === '/' ? api + endpoint : ''
  }

  window.PanoFallback = { resolve: resolve }

  function csrf() {
    if (csrfToken !== undefined) {
      return Promise.resolve(csrfToken)
    }

    return fetch(api + '/auth/csrf', { credentials: 'same-origin', headers: { Accept: 'application/json' } })
      .then(function (response) {
        return response.ok ? response.json() : null
      })
      .then(function (body) {
        return (body && body.csrfToken) || null
      })
      .catch(function () {
        return null
      })
      .then(function (token) {
        csrfToken = token

        return token
      })
  }

  /** POSTs JSON; resolves with the body, rejects with an Error that has code, details and status. */
  function post(path, body) {
    return csrf()
      .then(function (token) {
        var headers = { 'Content-Type': 'application/json', Accept: 'application/json' }

        if (token) {
          headers['X-CSRF-Token'] = token
        }

        var url = resolve(path)

        if (!url) {
          throw new Error('BAD_ENDPOINT')
        }

        return fetch(url, {
          method: 'POST',
          credentials: 'same-origin',
          headers: headers,
          body: JSON.stringify(body)
        })
      })
      .then(function (response) {
        return response.text().then(function (text) {
          var data = null

          try {
            data = text ? JSON.parse(text) : {}
          } catch (e) {
            data = null
          }

          if (response.ok) {
            return data || {}
          }

          var detail = (data && data.error) || {}
          var error = new Error(detail.code || 'HTTP_' + response.status)

          error.code = detail.code || 'HTTP_' + response.status
          error.details = detail.details || {}
          error.status = response.status

          throw error
        })
      })
  }

  function messageOf(error) {
    if (!error || error.status === undefined) {
      return errors.NETWORK || 'Could not reach the server.'
    }

    return pageErrors[error.code] || errors[error.code] || errors.GENERIC || error.code
  }

  function fieldsOf(form) {
    var body = {}

    Array.prototype.forEach.call(form.elements, function (element) {
      if (!element.name) {
        return
      }

      body[element.name] = element.type === 'checkbox' ? element.checked : element.value
    })

    return body
  }

  function messageBox(root) {
    return root.querySelector('[data-pano-message]')
  }

  function setMessage(root, text, kind) {
    var box = messageBox(root)

    if (!box) {
      return
    }

    box.textContent = text || ''
    box.hidden = !text
    box.setAttribute('data-kind', kind || 'error')
  }

  function setBusy(form, busy) {
    var button = form.querySelector('button[type="submit"]')

    if (!button) {
      return
    }

    if (busy) {
      button.setAttribute('data-label', button.textContent)
      button.textContent = form.getAttribute('data-pano-busy') || '...'
    } else if (button.hasAttribute('data-label')) {
      button.textContent = button.getAttribute('data-label')
    }

    button.disabled = busy
  }

  function finish(form) {
    form.querySelectorAll('[data-pano-hide-on-done]').forEach(function (element) {
      element.hidden = true
    })

    form.querySelectorAll('[data-pano-done]').forEach(function (element) {
      element.hidden = false
    })

    setMessage(form, '')
  }

  // --- single-action forms (activation, new e-mail, password renewal) -------

  function initAction(form) {
    var endpoint = form.getAttribute('data-pano-endpoint')
    var running = false

    function submit() {
      if (running) {
        return
      }

      var body = fieldsOf(form)

      if (form.querySelector('[name="token"]') && !body.token) {
        setMessage(form, errors.INVALID_LINK || 'This link is not valid.')

        return
      }

      running = true
      setBusy(form, true)
      setMessage(form, '')

      post(endpoint, body)
        .then(function () {
          finish(form)
        })
        .catch(function (error) {
          setMessage(form, messageOf(error))
        })
        .then(function () {
          running = false
          setBusy(form, false)
        })
    }

    form.addEventListener('submit', function (event) {
      event.preventDefault()
      submit()
    })

    if (form.hasAttribute('data-pano-auto')) {
      submit()
    }
  }

  // --- sign-in ---------------------------------------------------------------

  /** A site path (/x, never //x or /\x) or '' - the only kind of place a sign-in may send the visitor. */
  function safePath(value) {
    if (typeof value !== 'string' || value.charAt(0) !== '/' || value.charAt(1) === '/' || value.charAt(1) === '\\') {
      return ''
    }

    return value
  }

  /**
   * Steps (forms or blocks with data-pano-step): credentials -> done, or
   *   credentials -> challenge   a deny with details.challengeField asks for one more code (any plugin);
   *   credentials -> link -> register   the account has no password and no e-mail (LINK_CODE_REQUIRED);
   *   register -> verify         the new e-mail must be confirmed first.
   */
  function initLogin(root) {
    var steps = {}
    var state = { step: 'credentials', username: '', password: '', field: '', linkToken: '' }

    root.querySelectorAll('[data-pano-step]').forEach(function (element) {
      steps[element.getAttribute('data-pano-step')] = element
    })

    function show(name) {
      state.step = name

      Object.keys(steps).forEach(function (key) {
        steps[key].hidden = key !== name
      })

      var input = steps[name].querySelector('input:not([type="hidden"])')

      if (input) {
        input.focus()
      }
    }

    function done() {
      window.location.assign(safePath(page.next) || config.home || '/')
    }

    function run(form, request, onSuccess, onError) {
      setBusy(form, true)
      setMessage(root, '')

      request()
        .then(onSuccess)
        .catch(function (error) {
          if (!onError || !onError(error)) {
            setMessage(root, messageOf(error))
          }
        })
        .then(function () {
          setBusy(form, false)
        })
    }

    function login(form, extra) {
      var body = { usernameOrEmail: state.username }

      if (state.password) {
        body.password = state.password
      }

      if (state.field && extra !== undefined) {
        body[state.field] = extra
      }

      run(form, function () { return post('/auth/login', body) }, done, function (error) {
        if (error.code === 'LINK_CODE_REQUIRED') {
          show('link')

          return true
        }

        if (error.code === 'PLUGIN_DENIED_LOGIN') {
          var field = error.details && error.details.challengeField

          if (state.step === 'challenge') {
            setMessage(root, errors.CHALLENGE_INVALID || messageOf(error))

            return true
          }

          if (typeof field === 'string' && /^[A-Za-z][A-Za-z0-9_]{0,31}$/.test(field)) {
            state.field = field
            show('challenge')

            return true
          }
        }

        return false
      })
    }

    function on(name, handler) {
      if (!steps[name]) {
        return
      }

      steps[name].addEventListener('submit', function (event) {
        event.preventDefault()
        handler(steps[name], fieldsOf(steps[name]))
      })
    }

    on('credentials', function (form, fields) {
      state.username = fields.usernameOrEmail.trim()
      state.password = fields.password
      state.field = ''

      login(form)
    })

    on('challenge', function (form, fields) {
      login(form, fields.challenge.trim())
    })

    on('link', function (form, fields) {
      run(form, function () {
        return post('/auth/verify-link-code', { username: state.username, code: fields.code.trim() })
      }, function (result) {
        state.linkToken = result.token || ''
        show('register')
      })
    })

    on('register', function (form, fields) {
      run(form, function () {
        return post('/auth/register', {
          email: fields.email.trim(),
          password: fields.password,
          passwordRepeat: fields.passwordRepeat,
          agreement: fields.agreement === true,
          registerWithLinkToken: state.linkToken
        })
      }, function (result) {
        if (result.login) {
          done()
        } else {
          show('verify')
        }
      })
    })

    show('credentials')
  }

  // --- start -----------------------------------------------------------------

  document.querySelectorAll('[data-pano-logo]').forEach(function (image) {
    image.addEventListener('error', function () {
      image.hidden = true
    })
  })

  document.querySelectorAll('form[data-pano-endpoint]').forEach(initAction)
  document.querySelectorAll('[data-pano-flow="login"]').forEach(initLogin)
})()
