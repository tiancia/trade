import { ArrowRight, ChevronLeft, Grid2X2, Inbox, ListFilter, MessageCircle, Search, Tag, Trash2, X } from 'lucide-react';
import type { MarketplaceCategory, MarketplaceItem } from '../api';
import { formatDate } from '../time';
import type { useItemListings } from './useItemListings';

type ItemBrowserProps = {
  listings: ReturnType<typeof useItemListings>;
  categories: MarketplaceCategory[];
  currentUserId: number | undefined;
  contacting: boolean;
  onToggleMine: () => void;
  onPublish: () => void;
  onContact: (item: MarketplaceItem) => void;
};

export function ItemBrowser({ listings, categories, currentUserId, contacting, onToggleMine, onPublish, onContact }: ItemBrowserProps) {
  const {
    items, itemsLoading, selectedItem, selectItem, detailOpen, setDetailOpen,
    mine, queryInput, setQueryInput, selectedCategoryId, setSelectedCategoryId, delisting, delist,
  } = listings;

  return (
    <section className="marketplace-market" id="market">
      <div className="marketplace-market-heading">
        <div>
          <span className="marketplace-kicker">MARKETPLACE</span>
          <h2>{mine ? '我的闲置' : '发现闲置好物'}</h2>
        </div>
        <span className="marketplace-result-count">
          {itemsLoading ? '正在寻找好物…' : `当前展示 ${items.length} 件`}
        </span>
      </div>

      <div className="marketplace-toolbar">
        <label className="marketplace-search">
          <Search size={19} />
          <input
            aria-label="搜索商品"
            placeholder="搜索想要的好物"
            value={queryInput}
            maxLength={80}
            onChange={(event) => setQueryInput(event.target.value)}
          />
          {queryInput && (
            <button type="button" aria-label="清空搜索" onClick={() => setQueryInput('')}>
              <X size={16} />
            </button>
          )}
        </label>
        <button
          className={`marketplace-toggle ${mine ? 'active' : ''}`}
          type="button"
          onClick={onToggleMine}
        >
          <ListFilter size={17} /> {mine ? '查看全部' : '我的商品'}
        </button>
      </div>

      <div className="marketplace-categories" aria-label="商品分类">
        <button
          type="button"
          className={selectedCategoryId === null ? 'active' : ''}
          onClick={() => setSelectedCategoryId(null)}
        >
          <Grid2X2 size={16} /> 全部
        </button>
        {categories.map((category) => (
          <button
            key={category.id}
            type="button"
            className={selectedCategoryId === category.id ? 'active' : ''}
            onClick={() => setSelectedCategoryId(category.id)}
          >
            <Tag size={15} /> {category.name}
          </button>
        ))}
      </div>

      <div className="marketplace-content">
        <section className="marketplace-grid" aria-label="商品列表" aria-busy={itemsLoading}>
          {itemsLoading && items.length === 0 && Array.from({ length: 8 }, (_, index) => (
            <div className="marketplace-item-skeleton" key={index} aria-hidden="true">
              <span /><i /><i /><b />
            </div>
          ))}

          {items.map((item) => (
            <article
              className={`marketplace-item-card ${selectedItem?.id === item.id ? 'selected' : ''}`}
              key={item.id}
            >
              <button type="button" onClick={() => selectItem(item)} aria-label={`查看 ${item.title}`}>
                <figure>
                  <img src={item.imageUrl} alt={item.title} loading="lazy" decoding="async" />
                  <span className={`marketplace-status ${item.status === 'LISTED' ? 'listed' : 'delisted'}`}>
                    {item.status === 'LISTED' ? '在售' : '已下架'}
                  </span>
                </figure>
                <span className="marketplace-item-copy">
                  <small>{item.category.name}</small>
                  <strong>{item.title}</strong>
                  <span className="marketplace-item-meta">{item.seller.displayName} · {formatDate(item.updatedAt)}</span>
                  <span className="marketplace-item-footer">
                    <b>{formatPrice(item.price)}</b>
                    <i>查看详情 <ArrowRight size={14} /></i>
                  </span>
                </span>
              </button>
            </article>
          ))}

          {!itemsLoading && !items.length && (
            <div className="marketplace-empty">
              <span><Inbox size={30} /></span>
              <strong>{mine ? '还没有发布过商品' : '没有找到相关好物'}</strong>
              <p>{mine ? '拍张照片，让闲置开启下一段旅程。' : '换个关键词或分类试试看。'}</p>
              {mine && (
                <button className="marketplace-primary" type="button" onClick={onPublish}>
                  发布第一件闲置
                </button>
              )}
            </div>
          )}
        </section>

        <button
          className={`marketplace-detail-backdrop ${detailOpen ? 'open' : ''}`}
          type="button"
          aria-label="关闭商品详情"
          onClick={() => setDetailOpen(false)}
        />
        <aside className={`marketplace-detail ${detailOpen ? 'open' : ''}`} aria-label="商品详情">
          {selectedItem ? (
            <>
              <div className="marketplace-detail-mobile-head">
                <button type="button" onClick={() => setDetailOpen(false)} aria-label="返回商品列表">
                  <ChevronLeft size={21} />
                </button>
                <strong>商品详情</strong>
                <span />
              </div>
              <figure className="marketplace-detail-image">
                <img src={selectedItem.imageUrl} alt={selectedItem.title} />
                <span className={`marketplace-status ${selectedItem.status === 'LISTED' ? 'listed' : 'delisted'}`}>
                  {selectedItem.status === 'LISTED' ? '在售' : '已下架'}
                </span>
              </figure>
              <div className="marketplace-detail-body">
                <div className="marketplace-detail-head">
                  <span>{selectedItem.category.name}</span>
                  <strong>{formatPrice(selectedItem.price)}</strong>
                </div>
                <h2>{selectedItem.title}</h2>
                <p>{selectedItem.description || '卖家暂未补充更多描述。'}</p>
                <div className="marketplace-seller-card">
                  <span className="marketplace-avatar">{selectedItem.seller.displayName.slice(0, 1)}</span>
                  <div>
                    <small>卖家</small>
                    <strong>{selectedItem.seller.displayName}</strong>
                  </div>
                  <span>{formatDate(selectedItem.updatedAt)} 更新</span>
                </div>
                <div className="marketplace-detail-actions">
                  {currentUserId === selectedItem.seller.id && selectedItem.status === 'LISTED' ? (
                    <button
                      className="marketplace-danger"
                      type="button"
                      disabled={delisting}
                      onClick={() => void delist(selectedItem)}
                    >
                      <Trash2 size={17} /> {delisting ? '处理中…' : '下架商品'}
                    </button>
                  ) : (
                    <button
                      className="marketplace-primary"
                      type="button"
                      disabled={selectedItem.status !== 'LISTED' || contacting}
                      onClick={() => void onContact(selectedItem)}
                    >
                      <MessageCircle size={17} />
                      {contacting ? '正在打开…' : '联系卖家'}
                    </button>
                  )}
                </div>
              </div>
            </>
          ) : (
            <div className="marketplace-empty marketplace-detail-empty">
              <span><Inbox size={30} /></span>
              <strong>选择一件商品查看详情</strong>
            </div>
          )}
        </aside>
      </div>
    </section>
  );
}

function formatPrice(price: number | null) {
  return price == null
    ? '面议'
    : `¥${price.toLocaleString('zh-CN', {
        minimumFractionDigits: price % 1 ? 2 : 0,
        maximumFractionDigits: 2,
      })}`;
}
