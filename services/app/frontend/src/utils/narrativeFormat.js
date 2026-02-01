export function escapeHtml(value) {
  return String(value)
    .replaceAll('&', '&amp;')
    .replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;')
    .replaceAll('"', '&quot;')
    .replaceAll("'", '&#039;')
}

export function formatInline(value) {
  const escaped = escapeHtml(value)
  return escaped.replace(/\*\*(.*?)\*\*/g, '<strong>$1</strong>').replace(/\n/g, '<br>')
}

export function formatNarrative(text) {
  if (!text) {
    return ''
  }
  const normalized = text.replace(/\r\n/g, '\n')
  const bulletPrepared = normalized
    .replace(/:\s*-\s+/g, ':\n- ')
    .replace(/([^\n]) - (?=\*\*)/g, '$1\n- ')
  const lines = bulletPrepared.split('\n')
  let html = ''
  let listOpen = false

  lines.forEach((rawLine) => {
    const line = rawLine.trim()
    if (!line) {
      if (listOpen) {
        html += '</ul>'
        listOpen = false
      }
      return
    }
    if (line.startsWith('- ')) {
      if (!listOpen) {
        html += '<ul>'
        listOpen = true
      }
      html += `<li>${formatInline(line.slice(2))}</li>`
    } else {
      if (listOpen) {
        html += '</ul>'
        listOpen = false
      }
      html += `<p>${formatInline(line)}</p>`
    }
  })

  if (listOpen) {
    html += '</ul>'
  }

  return html
}
