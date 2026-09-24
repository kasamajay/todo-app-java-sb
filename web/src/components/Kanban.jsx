import { useEffect, useState } from 'react'
import { api } from '../api.js'
import { colors, cardStyle, fontFamily, pageStyle } from '../theme.js'
import Button from './common/Button.jsx'
import TaskCard from './TaskCard.jsx'
import TaskForm from './TaskForm.jsx'
import LabelsManager from './LabelsManager.jsx'

const COLUMNS = [
  { status: 'todo', title: 'To do' },
  { status: 'in_progress', title: 'In progress' },
  { status: 'done', title: 'Done' },
]

export default function Kanban({ board, onBack, onLogout }) {
  const [tasks, setTasks] = useState([])
  const [labels, setLabels] = useState([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [formOpen, setFormOpen] = useState(false)
  const [editingTask, setEditingTask] = useState(null)
  const [labelsManagerOpen, setLabelsManagerOpen] = useState(false)
  const [activeLabelFilter, setActiveLabelFilter] = useState(null)

  useEffect(() => {
    refresh()
  }, [board.id])

  async function refresh() {
    setLoading(true)
    try {
      const [taskData, labelData] = await Promise.all([api.listTasks(board.id), api.listLabels(board.id)])
      setTasks(taskData || [])
      setLabels(labelData || [])
    } catch (err) {
      setError(err.message || 'Failed to load tasks')
    } finally {
      setLoading(false)
    }
  }

  async function refreshLabels() {
    try {
      const data = await api.listLabels(board.id)
      setLabels(data || [])
    } catch (err) {
      setError(err.message || 'Failed to load labels')
    }
  }

  async function handleDrop(taskId, newStatus) {
    const task = tasks.find((t) => t.id === taskId)
    if (!task || task.status === newStatus) return

    const previous = tasks
    setTasks((prev) => prev.map((t) => (t.id === taskId ? { ...t, status: newStatus } : t)))
    try {
      await api.updateTask(taskId, { status: newStatus })
    } catch (err) {
      setTasks(previous)
      setError(err.message || 'Failed to move task')
    }
  }

  function openCreate() {
    setEditingTask(null)
    setFormOpen(true)
  }

  function openEdit(task) {
    setEditingTask(task)
    setFormOpen(true)
  }

  function closeForm() {
    setFormOpen(false)
    setEditingTask(null)
  }

  async function handleSave(values) {
    if (editingTask) {
      const updated = await api.updateTask(editingTask.id, values)
      setTasks((prev) => prev.map((t) => (t.id === updated.id ? updated : t)))
    } else {
      const created = await api.createTask({ ...values, board_id: board.id })
      setTasks((prev) => [...prev, created])
    }
    closeForm()
  }

  async function handleDelete(task) {
    if (!confirm(`Delete task "${task.title}"?`)) return
    try {
      await api.deleteTask(task.id)
      setTasks((prev) => prev.filter((t) => t.id !== task.id))
      closeForm()
    } catch (err) {
      setError(err.message || 'Failed to delete task')
    }
  }

  const visibleTasks = activeLabelFilter
    ? tasks.filter((t) => (t.label_ids || []).includes(activeLabelFilter))
    : tasks

  return (
    <div style={{ ...pageStyle, padding: '24px' }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
        <h1 style={{ margin: 0, fontSize: '22px', color: colors.brand, fontFamily }}>Todo App</h1>
        <button style={linkBtn} onClick={onLogout}>
          Log out
        </button>
      </div>

      <div style={{ display: 'flex', alignItems: 'center', gap: '12px', margin: '20px 0 4px' }}>
        <button style={linkBtn} onClick={onBack}>
          &larr; Boards
        </button>
        <button style={linkBtn} onClick={() => setLabelsManagerOpen(true)}>
          Manage labels
        </button>
      </div>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '20px' }}>
        <div>
          <h2 style={{ margin: '0 0 4px', fontSize: '20px' }}>{board.name}</h2>
          {board.summary && <p style={{ margin: 0, fontSize: '13px', color: colors.textMuted }}>{board.summary}</p>}
        </div>
        <Button onClick={openCreate}>+ New task</Button>
      </div>

      {loading && <p style={{ color: colors.textMuted }}>Loading…</p>}
      {error && <p style={{ color: colors.danger }}>{error}</p>}

      {labels.length > 0 && (
        <div style={{ display: 'flex', gap: '8px', flexWrap: 'wrap', marginBottom: '16px' }}>
          <FilterChip label="All" active={activeLabelFilter === null} onClick={() => setActiveLabelFilter(null)} />
          {labels.map((l) => (
            <FilterChip
              key={l.id}
              label={l.name}
              color={l.color}
              active={activeLabelFilter === l.id}
              onClick={() => setActiveLabelFilter(activeLabelFilter === l.id ? null : l.id)}
            />
          ))}
        </div>
      )}

      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(3, minmax(0, 1fr))', gap: '16px' }}>
        {COLUMNS.map((col) => (
          <Column
            key={col.status}
            title={col.title}
            status={col.status}
            tasks={visibleTasks.filter((t) => t.status === col.status)}
            labels={labels}
            onDropTask={handleDrop}
            onTaskClick={openEdit}
          />
        ))}
      </div>

      {formOpen && (
        <TaskForm
          task={editingTask}
          labels={labels}
          onSave={handleSave}
          onDelete={editingTask ? handleDelete : undefined}
          onClose={closeForm}
        />
      )}

      {labelsManagerOpen && (
        <LabelsManager
          board={board}
          labels={labels}
          onChange={refreshLabels}
          onClose={() => setLabelsManagerOpen(false)}
        />
      )}
    </div>
  )
}

function FilterChip({ label, color, active, onClick }) {
  return (
    <button
      type="button"
      onClick={onClick}
      style={{
        display: 'flex',
        alignItems: 'center',
        gap: '6px',
        fontSize: '12px',
        fontWeight: 600,
        border: `1.5px solid ${active ? colors.brand : colors.border}`,
        background: active ? colors.brandLight : '#ffffff',
        color: active ? colors.brandDark : colors.text,
        borderRadius: '999px',
        padding: '4px 12px',
        cursor: 'pointer',
      }}
    >
      {color && <span style={{ width: '8px', height: '8px', borderRadius: '50%', background: color }} />}
      {label}
    </button>
  )
}

function Column({ title, status, tasks, labels, onDropTask, onTaskClick }) {
  const [dragOver, setDragOver] = useState(false)

  function handleDragOver(e) {
    e.preventDefault()
    e.dataTransfer.dropEffect = 'move'
    if (!dragOver) setDragOver(true)
  }

  function handleDrop(e) {
    e.preventDefault()
    setDragOver(false)
    const taskId = e.dataTransfer.getData('text/plain')
    if (taskId) onDropTask(taskId, status)
  }

  return (
    <div
      onDragOver={handleDragOver}
      onDragLeave={() => setDragOver(false)}
      onDrop={handleDrop}
      style={{
        ...cardStyle,
        background: dragOver ? colors.brandLight : colors.surface,
        padding: '12px',
        minHeight: '300px',
        transition: 'background 0.1s ease',
      }}
    >
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '10px' }}>
        <h3 style={{ margin: 0, fontSize: '13px', textTransform: 'uppercase', letterSpacing: '0.04em', color: colors.textMuted }}>
          {title}
        </h3>
        <span style={{ fontSize: '12px', color: colors.textMuted }}>{tasks.length}</span>
      </div>
      {tasks.map((task) => (
        <TaskCard key={task.id} task={task} labels={labels} onClick={onTaskClick} />
      ))}
    </div>
  )
}

const linkBtn = {
  background: 'none',
  border: 'none',
  padding: 0,
  color: colors.brand,
  cursor: 'pointer',
  fontSize: '13px',
  fontWeight: 600,
}
