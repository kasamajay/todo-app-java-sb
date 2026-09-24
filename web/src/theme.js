export const BRAND = '#6366f1'

export const colors = {
  brand: BRAND,
  brandDark: '#4f46e5',
  brandLight: '#eef2ff',
  bg: '#f9fafb',
  surface: '#ffffff',
  border: '#e5e7eb',
  text: '#111827',
  textMuted: '#6b7280',
  danger: '#ef4444',
  dangerLight: '#fee2e2',
  warning: '#f59e0b',
  warningLight: '#fef3c7',
  success: '#16a34a',
}

// labelColors is the fixed palette task labels may use - mirrors
// api/internal/models.LabelColors exactly (same hex values, same order).
export const labelColors = [
  { value: colors.brand, name: 'Indigo' },
  { value: colors.danger, name: 'Red' },
  { value: colors.warning, name: 'Amber' },
  { value: colors.success, name: 'Green' },
  { value: '#0ea5e9', name: 'Blue' },
  { value: '#8b5cf6', name: 'Purple' },
  { value: '#ec4899', name: 'Pink' },
  { value: '#6b7280', name: 'Gray' },
]

export const fontFamily =
  "-apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Helvetica, Arial, sans-serif"

export function spacing(n) {
  return `${n * 4}px`
}

export const pageStyle = {
  fontFamily,
  color: colors.text,
  background: colors.bg,
  minHeight: '100vh',
  boxSizing: 'border-box',
}

export const cardStyle = {
  background: colors.surface,
  border: `1px solid ${colors.border}`,
  borderRadius: '10px',
  boxShadow: '0 1px 2px rgba(0,0,0,0.04)',
}

export const primaryButtonStyle = {
  background: colors.brand,
  color: '#ffffff',
  border: 'none',
  borderRadius: '8px',
  padding: '10px 16px',
  fontSize: '14px',
  fontWeight: 600,
  cursor: 'pointer',
}

export const secondaryButtonStyle = {
  background: '#ffffff',
  color: colors.text,
  border: `1px solid ${colors.border}`,
  borderRadius: '8px',
  padding: '10px 16px',
  fontSize: '14px',
  fontWeight: 600,
  cursor: 'pointer',
}

export const dangerButtonStyle = {
  ...secondaryButtonStyle,
  color: colors.danger,
  borderColor: colors.dangerLight,
}

export const inputStyle = {
  width: '100%',
  boxSizing: 'border-box',
  padding: '10px 12px',
  fontSize: '14px',
  border: `1px solid ${colors.border}`,
  borderRadius: '8px',
  outline: 'none',
}

export const labelStyle = {
  display: 'block',
  fontSize: '13px',
  fontWeight: 600,
  color: colors.textMuted,
  marginBottom: '6px',
}
