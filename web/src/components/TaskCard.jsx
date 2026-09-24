import { colors, cardStyle } from '../theme.js'

const DUE_SOON_MS = 24 * 60 * 60 * 1000

function getDueStatus(task) {
  if (!task.due_date || task.status === 'done') return null
  const diff = new Date(task.due_date).getTime() - Date.now()
  if (diff < 0) return 'overdue'
  if (diff <= DUE_SOON_MS) return 'soon'
  return null
}

export default function TaskCard({ task, labels = [], onClick }) {
  function handleDragStart(e) {
    e.dataTransfer.setData('text/plain', task.id)
    e.dataTransfer.effectAllowed = 'move'
  }

  const dueStatus = getDueStatus(task)
  const accentColor =
    dueStatus === 'overdue' ? colors.danger : dueStatus === 'soon' ? colors.warning : colors.brand
  const taskLabels = (task.label_ids || []).map((id) => labels.find((l) => l.id === id)).filter(Boolean)

  return (
    <div
      draggable
      onDragStart={handleDragStart}
      onClick={() => onClick(task)}
      style={{
        ...cardStyle,
        padding: '12px',
        marginBottom: '10px',
        cursor: 'grab',
        borderLeft: `3px solid ${accentColor}`,
      }}
    >
      {taskLabels.length > 0 && (
        <div style={{ display: 'flex', gap: '4px', flexWrap: 'wrap', marginBottom: '6px' }}>
          {taskLabels.map((l) => (
            <span
              key={l.id}
              style={{
                fontSize: '10px',
                fontWeight: 600,
                color: '#ffffff',
                background: l.color,
                borderRadius: '10px',
                padding: '2px 8px',
                lineHeight: 1.4,
              }}
            >
              {l.name}
            </span>
          ))}
        </div>
      )}
      <div style={{ fontSize: '14px', fontWeight: 600, marginBottom: task.description ? '4px' : 0 }}>
        {task.title}
      </div>
      {task.description && (
        <div
          style={{
            fontSize: '12px',
            color: colors.textMuted,
            overflow: 'hidden',
            textOverflow: 'ellipsis',
            display: '-webkit-box',
            WebkitLineClamp: 3,
            WebkitBoxOrient: 'vertical',
          }}
        >
          {task.description}
        </div>
      )}
      {task.due_date && (
        <div
          style={{
            fontSize: '11px',
            marginTop: '6px',
            fontWeight: 600,
            color: dueStatus === 'overdue' ? colors.danger : dueStatus === 'soon' ? colors.warning : colors.textMuted,
          }}
        >
          {dueStatus === 'overdue' ? 'Overdue · ' : dueStatus === 'soon' ? 'Due soon · ' : 'Due '}
          {new Date(task.due_date).toLocaleDateString(undefined, { timeZone: 'UTC' })}
        </div>
      )}
    </div>
  )
}
