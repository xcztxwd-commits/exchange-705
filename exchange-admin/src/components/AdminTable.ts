import { cloneVNode, defineComponent, Fragment, h, ref, watch, type VNode } from 'vue'
import { ElTable, ElDialog, ElButton, ElMessage } from 'element-plus'
import { useAuthStore } from '@/store/auth'
import request from '@/utils/request'
import { mergeColumns, moveColumn, type ColumnPreference, type TableColumn } from '@/utils/tablePreferences'
import './adminTable.css'

function flatten(nodes: VNode[]): VNode[] {
  return nodes.flatMap(node => node.type === Fragment && Array.isArray(node.children) ? flatten(node.children as VNode[]) : [node])
}

export default defineComponent({
  name: 'AdminTable',
  inheritAttrs: false,
  props: { tableKey: { type: String, required: true } },
  setup(props, { slots, attrs }) {
    const auth = useAuthStore()
    const saved = ref<ColumnPreference[]>([]), draft = ref<TableColumn[]>([])
    const editing = ref(false), saving = ref(false), ready = ref(false), error = ref('')
    const reload = ref(0)
    let dragged = -1
    watch([() => props.tableKey, () => auth.token, reload], async ([table, token], _, cleanup) => {
      let active = true
      cleanup(() => { active = false })
      saved.value = []; editing.value = false; ready.value = false; error.value = ''
      if (!token) return
      try {
        const result: any = await request.get(`/admin/table-preferences/${table}`)
        if (active) { saved.value = Array.isArray(result) ? result : []; ready.value = true }
      } catch (e: any) {
        if (active) error.value = e.message || '列配置加载失败'
      }
    }, { immediate: true })
    async function save() {
      if (!ready.value || saving.value) return
      const token = auth.token, table = props.tableKey
      const columns = draft.value.map(({ id, visible, fixed }) => ({ id, visible, fixed }))
      if (!columns.some(column => column.visible)) { ElMessage.warning('至少保留一列'); return }
      saving.value = true
      try {
        await request.put(`/admin/table-preferences/${table}`, columns)
        if (auth.token !== token || props.tableKey !== table) return
        saved.value = columns; editing.value = false
        ElMessage.success('列设置已保存至当前账号')
      } catch (e: any) { ElMessage.error(e.message || '保存失败，请重试') }
      finally { saving.value = false }
    }
    return () => {
      // Evaluate the original slot during render so reactive and permission-gated columns stay current.
      const nodes = flatten(slots.default?.() || [])
      const definitions: TableColumn[] = [], columnNodes = new Map<string, VNode>(), other: VNode[] = []
      const counts = new Map<string, number>()
      for (const node of nodes) {
        if ((node.type as any)?.name !== 'ElTableColumn') { other.push(node); continue }
        const p = node.props || {}, label = String(p.label || (p.type === 'selection' ? '选择' : p.type === 'index' ? '序号' : p.type === 'expand' ? '展开' : '未命名列'))
        const base = String(p['column-key'] || p.columnKey || p.prop || node.key || label)
        const occurrence = counts.get(base) || 0
        counts.set(base, occurrence + 1)
        const id = occurrence ? `${base}#${occurrence}` : base
        definitions.push({ id, label, visible: true, fixed: p.fixed === true || p.fixed === '' || p.fixed === 'left' ? 'left' : p.fixed === 'right' ? 'right' : '' })
        columnNodes.set(id, node)
      }
      const columns = mergeColumns(definitions, editing.value ? draft.value : saved.value)
      const shown = columns.filter(column => column.visible)
      const configurationKey = JSON.stringify(shown.map(({ id, fixed }) => [id, fixed]))
      const controls = h('div', { class: 'admin-table-toolbar' }, [
        error.value ? h('span', { role: 'alert', class: 'admin-table-error' }, [error.value, h(ElButton, { link: true, onClick: () => reload.value++ }, () => '重试')]) : null,
        h(ElButton, { size: 'small', disabled: !ready.value, onClick: () => { draft.value = mergeColumns(definitions, saved.value); editing.value = true } }, () => ready.value ? '列设置' : error.value ? '列设置不可用' : '加载列设置…'),
      ])
      const dialog = h(ElDialog, { modelValue: editing.value, 'onUpdate:modelValue': (v: boolean) => { if (!saving.value) editing.value = v }, title: '表格列设置', width: 'min(680px, 94vw)', closeOnClickModal: false }, {
        default: () => [
          h('p', { class: 'admin-table-help' }, '勾选显示列；拖动或使用前移/后移排序；可固定左侧或右侧。保存后按当前账号同步。固定列会显示在对应侧。'),
          h('div', { class: 'admin-table-column-list' }, draft.value.map((column, index) => h('div', {
            key: column.id, class: 'admin-table-column', draggable: !saving.value,
            onDragstart: (e: DragEvent) => { dragged = index; e.dataTransfer?.setData('text/plain', column.id) },
            onDragover: (e: DragEvent) => e.preventDefault(),
            onDrop: (e: DragEvent) => { e.preventDefault(); if (!saving.value) draft.value = moveColumn(draft.value, dragged, index); dragged = -1 },
            onDragend: () => { dragged = -1 },
          }, [
            h('label', { class: 'admin-table-column-label' }, [h('input', { type: 'checkbox', checked: column.visible, disabled: saving.value, onChange: (e: Event) => { column.visible = (e.target as HTMLInputElement).checked } }), column.label]),
            h('select', { value: column.fixed, disabled: saving.value, 'aria-label': `${column.label}固定位置`, onChange: (e: Event) => { column.fixed = (e.target as HTMLSelectElement).value as ColumnPreference['fixed'] } }, [h('option', { value: '' }, '不固定'), h('option', { value: 'left' }, '固定左侧'), h('option', { value: 'right' }, '固定右侧')]),
            h(ElButton, { size: 'small', disabled: saving.value || index === 0, 'aria-label': `${column.label}前移`, onClick: () => { draft.value = moveColumn(draft.value, index, index - 1) } }, () => '前移'),
            h(ElButton, { size: 'small', disabled: saving.value || index === draft.value.length - 1, 'aria-label': `${column.label}后移`, onClick: () => { draft.value = moveColumn(draft.value, index, index + 1) } }, () => '后移'),
          ]))),
        ],
        footer: () => [
          h(ElButton, { disabled: saving.value, onClick: () => { draft.value = mergeColumns(definitions, []) } }, () => '恢复默认'),
          h(ElButton, { disabled: saving.value, onClick: () => { editing.value = false } }, () => '取消'),
          h(ElButton, { type: 'primary', loading: saving.value, onClick: save }, () => '保存'),
        ],
      })
      return h('div', { class: 'admin-table-container' }, [controls,
        h(ElTable, { ...attrs, key: configurationKey }, { ...slots, default: () => [
          ...shown.map(column => cloneVNode(columnNodes.get(column.id)!, { key: column.id, fixed: column.fixed || false })), ...other,
        ] }), dialog,
      ])
    }
  },
})
