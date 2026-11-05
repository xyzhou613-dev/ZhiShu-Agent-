package com.bemodel.notice;

import com.bemodel.common.PageResult;
import com.bemodel.common.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/notice")
@RequiredArgsConstructor
public class NoticeController {

    private final NoticeService noticeService;

    @GetMapping("/unread-count")
    public Result<Map<String, Object>> unreadCount() {
        return Result.ok(Map.of("count", noticeService.unreadCount()));
    }

    @GetMapping("/list")
    public Result<PageResult<AlertNotice>> list(@RequestParam(required = false) Integer pageNum,
                                                @RequestParam(required = false) Integer pageSize) {
        return Result.ok(noticeService.page(
                PageResult.pageNum(pageNum), PageResult.pageSize(pageSize, 20)));
    }

    @PostMapping("/{id}/read")
    public Result<Void> markRead(@PathVariable Long id) {
        noticeService.markRead(id);
        return Result.ok();
    }

    @PostMapping("/read-all")
    public Result<Map<String, Object>> markAllRead() {
        return Result.ok(Map.of("marked", noticeService.markAllRead()));
    }
}
