// 元素状态字典（概念/规则/动作共用同一状态机）
export const statusText = (s) =>
  ({ DRAFT: '草稿', REVIEW: '评审中', PUBLISHED: '已发布', DEPRECATED: '已废弃' }[s] || s)

export const statusTagType = (s) =>
  ({ DRAFT: 'info', REVIEW: 'warning', PUBLISHED: 'success', DEPRECATED: 'danger' }[s] || 'info')
