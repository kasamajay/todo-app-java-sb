import { inputStyle, labelStyle } from '../../theme.js'

export default function TextField({ label, style, containerStyle, ...rest }) {
  return (
    <div style={{ marginBottom: '14px', ...containerStyle }}>
      {label && <label style={labelStyle}>{label}</label>}
      <input style={{ ...inputStyle, ...style }} {...rest} />
    </div>
  )
}
