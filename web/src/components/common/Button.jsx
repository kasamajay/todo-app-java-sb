import { primaryButtonStyle, secondaryButtonStyle, dangerButtonStyle } from '../../theme.js'

const variants = {
  primary: primaryButtonStyle,
  secondary: secondaryButtonStyle,
  danger: dangerButtonStyle,
}

export default function Button({ variant = 'primary', style, disabled, children, ...rest }) {
  return (
    <button
      {...rest}
      disabled={disabled}
      style={{
        ...variants[variant],
        opacity: disabled ? 0.6 : 1,
        cursor: disabled ? 'default' : 'pointer',
        ...style,
      }}
    >
      {children}
    </button>
  )
}
